package com.udyam.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class InsightsServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final InMemoryBackofficeStore store = new InMemoryBackofficeStore();
    private final InsightsService service = new InsightsService(store, new PropensityScorer(JSON), JSON);

    private BackofficeViews.SegmentEconomics segment(String name) {
        return service.insights().segmentEconomics().stream().filter(s -> s.segment().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void funnelMatchesTheKnownNumbers() {
        assertThat(service.insights().funnel().stream().map(BackofficeViews.FunnelStage::leads).toList())
                .containsExactly(5000L, 3406L, 1974L, 861L, 588L, 368L);
        assertThat(service.insights().funnel().get(0).stage()).isEqualTo("Lead");
    }

    @Test
    void activationsPerSegmentMatchTheKnownNumbers() {
        List<BackofficeViews.SegmentEconomics> segments = service.insights().segmentEconomics();
        assertThat(segments.stream().map(BackofficeViews.SegmentEconomics::activations).toList()).containsExactly(110L, 138L, 83L, 37L);
        assertThat(segments.get(0).segment()).isEqualTo(LeadRouter.MICRO_SEGMENT);
    }

    @Test
    void microEconomicsShowTheLongPayback() {
        BackofficeViews.SegmentEconomics micro = segment(LeadRouter.MICRO_SEGMENT);
        assertThat(micro.leads()).isEqualTo(2709);
        assertThat(micro.conversion()).isCloseTo(4.1, within(0.05));
        assertThat(micro.cacPerActivation()).isEqualTo(20007);
        assertThat(micro.paybackMonths()).isCloseTo(8.0, within(0.05));
        assertThat(micro.paybackMonths()).isGreaterThan(segment("Professional offices").paybackMonths());
    }

    @Test
    void sourcesAreRankedByConversion() {
        List<BackofficeViews.SourceEconomics> sources = service.insights().sourceEconomics();
        assertThat(sources).hasSize(6);
        assertThat(sources.get(0).source()).isEqualTo("Referral");
        assertThat(sources.get(0).conversion()).isCloseTo(14.8, within(0.05));
        assertThat(sources.stream().mapToLong(BackofficeViews.SourceEconomics::activations).sum()).isEqualTo(368);
    }

    @Test
    void misSellingIs48Of368() {
        BackofficeViews.MisSelling m = service.insights().misSelling();
        assertThat(m.bundlesWithViolations()).isEqualTo(48);
        assertThat(m.activatedChecked()).isEqualTo(368);
        assertThat(m.registryActiveViolations()).isEqualTo(56);
    }

    @Test
    void theModelCardReportsWhatTrainingMeasured() {
        BackofficeViews.ModelCard card = service.insights().model();
        assertThat(card.variant()).isEqualTo("b");
        assertThat(card.auc()).isCloseTo(0.699, within(0.001));
        assertThat(card.juneAuc()).isCloseTo(0.714, within(0.001));
        assertThat(card.top20Capture()).isCloseTo(0.375, within(0.002));
        assertThat(card.liftTable()).hasSize(10);
    }

    @Test
    void theLiveFunnelIsPassedThrough() {
        store.liveFunnel = new BackofficeStore.LiveFunnel(9, 7, 4, 2, 1);
        assertThat(service.insights().liveFunnel().kycVerified()).isEqualTo(2);
    }
}
