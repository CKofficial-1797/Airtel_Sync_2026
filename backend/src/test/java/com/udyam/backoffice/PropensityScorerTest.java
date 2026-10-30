package com.udyam.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PropensityScorerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PropensityScorer SCORER = new PropensityScorer(JSON);
    private static final double INTERCEPT = SCORER.modelInfo().get("intercept").asDouble();

    private static JsonNode parityRows() throws IOException {
        try (var in = new ClassPathResource("ml/propensity-parity.json").getInputStream()) {
            return JSON.readTree(in);
        }
    }

    static Map<String, Object> strong() {
        Map<String, Object> input = new HashMap<>();
        input.put("lead_source", "Referral");
        input.put("segment", "Small multi-site businesses");
        input.put("city_tier", 1);
        input.put("gstin_status", "ACTIVE");
        input.put("has_udyam", "Y");
        input.put("employee_count", 30);
        input.put("num_sites", 3);
        input.put("current_internet", "Leased line");
        return input;
    }

    @Test
    void matchesPythonOnThe200LeadFixture() throws IOException {
        JsonNode rows = parityRows();
        assertThat(rows).hasSize(200);
        for (JsonNode row : rows) {
            Map<String, Object> input = JSON.convertValue(row.get("inputFeatures"), new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
            assertThat(SCORER.score(input).probability()).as(row.get("leadId").asText())
                    .isCloseTo(row.get("expectedProbability").asDouble(), within(1e-6));
        }
    }

    @Test
    void reasonsAreAtMostThreeAndOrderedByStrength() {
        List<PropensityScorer.Reason> reasons = SCORER.score(strong()).topReasons();
        assertThat(reasons).hasSize(3);
        assertThat(Math.abs(reasons.get(0).contribution())).isGreaterThanOrEqualTo(Math.abs(reasons.get(1).contribution()));
        assertThat(Math.abs(reasons.get(1).contribution())).isGreaterThanOrEqualTo(Math.abs(reasons.get(2).contribution()));
        assertThat(reasons.get(0).labelEn()).isNotBlank();
        assertThat(reasons.get(0).labelHi()).isNotBlank();
        assertThat(reasons.get(0).direction()).isEqualTo("+");
    }

    @Test
    void aNegativeReasonIsMarkedAsOne() {
        Map<String, Object> input = new HashMap<>();
        input.put("segment", "Micro retail & services");
        PropensityScorer.Reason reason = SCORER.score(input).topReasons().get(0);
        assertThat(reason.direction()).isEqualTo("-");
        assertThat(reason.contribution()).isNegative();
    }

    @Test
    void aBigBusinessIsNeverToldItsSizeCountsAgainstIt() {
        // The score keeps the size coefficient; only the reason is hidden.
        Map<String, Object> small = new HashMap<>(strong());
        small.put("employee_count", 3);
        Map<String, Object> big = new HashMap<>(strong());
        big.put("employee_count", 200);
        big.put("num_sites", 10);
        assertThat(SCORER.score(big).topReasons()).noneMatch(r -> "-".equals(r.direction()) && r.labelEn().toLowerCase().contains("people"));
        assertThat(SCORER.score(big).probability()).isNotEqualTo(SCORER.score(small).probability());
    }

    @Test
    void bandThresholdsComeFromTheModel() {
        double hot = SCORER.modelInfo().get("bands").get("HOT").asDouble();
        double warm = SCORER.modelInfo().get("bands").get("WARM").asDouble();
        assertThat(SCORER.bandOf(hot)).isEqualTo(PropensityScorer.HOT);
        assertThat(SCORER.bandOf(hot - 1e-9)).isEqualTo(PropensityScorer.WARM);
        assertThat(SCORER.bandOf(warm)).isEqualTo(PropensityScorer.WARM);
        assertThat(SCORER.bandOf(warm - 1e-9)).isEqualTo(PropensityScorer.COLD);
        assertThat(SCORER.score(strong()).band()).isEqualTo(PropensityScorer.HOT);
    }

    @Test
    void missingAndUnknownValuesUseTheReferenceLevel() {
        double base = 1 / (1 + Math.exp(-INTERCEPT));
        PropensityScorer.Score empty = SCORER.score(Map.of());
        assertThat(empty.probability()).isCloseTo(base, within(1e-12));
        assertThat(empty.topReasons()).isEmpty();

        Map<String, Object> unknown = new HashMap<>();
        unknown.put("segment", "Space tourism");
        unknown.put("lead_source", "Carrier pigeon");
        unknown.put("employee_count", "many");
        assertThat(SCORER.score(unknown).probability()).isCloseTo(base, within(1e-12));
    }

    @Test
    void bucketEdgesAreInclusive() {
        double five = SCORER.score(Map.of("employee_count", 5)).probability();
        double six = SCORER.score(Map.of("employee_count", 6)).probability();
        double one = SCORER.score(Map.of("employee_count", 1)).probability();
        assertThat(five).isEqualTo(one);
        assertThat(six).isNotEqualTo(five);
    }

    @Test
    void scoreIsAPercentileThatRisesWithProbability() {
        PropensityScorer.Score low = SCORER.score(Map.of("segment", "Micro retail & services"));
        PropensityScorer.Score high = SCORER.score(strong());
        assertThat(low.score0to100()).isBetween(0, 100);
        assertThat(high.score0to100()).isBetween(80, 100); // top 20% is HOT
        assertThat(high.score0to100()).isGreaterThan(low.score0to100());
    }
}
