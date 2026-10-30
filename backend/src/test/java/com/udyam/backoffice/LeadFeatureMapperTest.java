package com.udyam.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udyam.rules.RulesFixture;
import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class LeadFeatureMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PropensityScorer SCORER = new PropensityScorer(JSON);
    private static final LeadFeatureMapper MAPPER = new LeadFeatureMapper(RulesFixture.PROVIDER, RulesFixture.GSTINS);

    private static JsonNode json(String text) throws IOException {
        return JSON.readTree(text);
    }

    @Test
    void historicalLeadsScoreExactlyAsTrainingDid() throws IOException {
        Map<String, BackofficeStore.HistoricalLead> leads = InMemoryBackofficeStore.LEADS.stream()
                .collect(Collectors.toMap(BackofficeStore.HistoricalLead::leadId, Function.identity()));
        JsonNode rows;
        try (var in = new ClassPathResource("ml/propensity-parity.json").getInputStream()) {
            rows = JSON.readTree(in);
        }
        for (JsonNode row : rows) {
            LeadProfile profile = MAPPER.fromHistorical(leads.get(row.get("leadId").asText()));
            assertThat(SCORER.score(profile.modelInput()).probability()).as(row.get("leadId").asText())
                    .isCloseTo(row.get("expectedProbability").asDouble(), within(1e-6));
        }
    }

    @Test
    void aSessionKnowsOnlyWhatTheOwnerSaid() throws IOException {
        LeadProfile p = MAPPER.fromSession(json("{\"businessType\":\"Kirana store\",\"city\":\"Nagpur\"}"));
        assertThat(p.leadSource()).isEqualTo(LeadFeatureMapper.LIVE_LEAD_SOURCE);
        assertThat(p.segment()).isEqualTo("Micro retail & services");
        assertThat(p.tier()).isEqualTo(RulesFixture.DATA.city("Nagpur").orElseThrow().tier());
        assertThat(p.employees()).isNull();
        assertThat(p.sites()).isNull();
        assertThat(p.hasUdyam()).isNull();
        assertThat(p.gstinStatus()).isEqualTo(LeadProfile.GST_NONE);
    }

    @Test
    void anEmptySessionScoresAsTheReferenceLead() {
        LeadProfile p = MAPPER.fromSession(null);
        double base = 1 / (1 + Math.exp(-SCORER.modelInfo().get("intercept").asDouble()));
        assertThat(SCORER.score(p.modelInput()).probability()).isCloseTo(base, within(1e-12));
    }

    @Test
    void anExplicitSegmentWinsAndAnUnknownBusinessTypeStaysUnknown() throws IOException {
        assertThat(MAPPER.fromSession(json("{\"segment\":\"PROF\",\"businessType\":\"Kirana store\"}")).segment()).isEqualTo("Professional offices");
        assertThat(MAPPER.fromSession(json("{\"businessType\":\"Space tourism\"}")).segment()).isNull();
    }

    @Test
    void gstinStatusComesFromTheRegistry() throws IOException {
        assertThat(MAPPER.fromSession(json("{\"gstin\":\"" + RulesFixture.ACTIVE_GSTIN + "\"}")).gstinStatus()).isEqualTo(LeadProfile.GST_ACTIVE);
        assertThat(MAPPER.fromSession(json("{\"gstin\":\"" + RulesFixture.CANCELLED_GSTIN + "\"}")).gstinStatus()).isEqualTo(LeadProfile.GST_INACTIVE);
        assertThat(MAPPER.fromSession(json("{\"gstin\":\"" + RulesFixture.SUSPENDED_GSTIN + "\"}")).gstinStatus()).isEqualTo(LeadProfile.GST_INACTIVE);
        assertThat(MAPPER.fromSession(json("{\"gstinDeclared\":true}")).gstinStatus()).isEqualTo(LeadProfile.GST_NONE);
        assertThat(MAPPER.fromSession(json("{\"gstin\":\"ABC\"}")).gstinStatus()).isEqualTo(LeadProfile.GST_NONE);
    }

    @Test
    void anOrderAddsWhatItsDocumentsShow() throws IOException {
        JsonNode profile = json("{\"businessType\":\"Dental clinic\",\"cityTier\":1,\"employees\":6,\"sites\":1,\"gstin\":\"" + RulesFixture.CANCELLED_GSTIN + "\"}");
        LeadProfile withoutDocs = MAPPER.fromOrder(profile, Map.of());
        assertThat(withoutDocs.gstinStatus()).isEqualTo(LeadProfile.GST_INACTIVE);
        assertThat(withoutDocs.hasUdyam()).isNull();

        LeadProfile withDocs = MAPPER.fromOrder(profile, Map.of(
                "GST_CERTIFICATE", json("{\"gstin\":\"" + RulesFixture.ACTIVE_GSTIN + "\"}"),
                "UDYAM_CERTIFICATE", json("{\"udyamNumber\":\"UDYAM-KL-01-0000001\"}")));
        assertThat(withDocs.gstinStatus()).isEqualTo(LeadProfile.GST_ACTIVE);
        assertThat(withDocs.hasUdyam()).isTrue();
        assertThat(withDocs.segment()).isEqualTo("Professional offices");
        assertThat(withDocs.tier()).isEqualTo(1);
        assertThat(withDocs.employees()).isEqualTo(6);
    }
}
