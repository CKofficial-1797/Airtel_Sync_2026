package com.udyam.backoffice;

import static com.udyam.backoffice.BackofficeViews.FunnelStage;
import static com.udyam.backoffice.BackofficeViews.Insights;
import static com.udyam.backoffice.BackofficeViews.MisSelling;
import static com.udyam.backoffice.BackofficeViews.ModelCard;
import static com.udyam.backoffice.BackofficeViews.SegmentEconomics;
import static com.udyam.backoffice.BackofficeViews.SourceEconomics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udyam.leads.DataSummaryService;
import com.udyam.rules.audit.MisSellingReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** The numbers behind the business case, all computed from the provided leads and our own tables. */
@Service
public class InsightsService {

    private final BackofficeStore store;
    private final PropensityScorer scorer;
    private final ObjectMapper json;

    /** Provided data never changes: compute it once. Only the live funnel is read each time. */
    private volatile Insights fromProvidedData;

    public InsightsService(BackofficeStore store, PropensityScorer scorer, ObjectMapper json) {
        this.store = store;
        this.scorer = scorer;
        this.json = json;
    }

    public Insights insights() {
        Insights provided = fromProvidedData;
        if (provided == null) {
            List<BackofficeStore.HistoricalLead> leads = store.historicalLeads();
            MisSellingReport report = store.misSelling();
            MisSellingReport.Variant flag = report.hasGstinFlag();
            provided = new Insights(funnel(leads), segments(leads), sources(leads),
                    new MisSelling(flag.activatedChecked(), flag.bundlesWithViolations(), flag.misSoldPercent().doubleValue(), report.registryActive().bundlesWithViolations()),
                    modelCard(), null);
            fromProvidedData = provided;
        }
        return new Insights(provided.funnel(), provided.segmentEconomics(), provided.sourceEconomics(), provided.misSelling(), provided.model(), store.liveFunnel());
    }

    /** Leads that reached at least each stage, same definition as the data summary. */
    static List<FunnelStage> funnel(List<BackofficeStore.HistoricalLead> leads) {
        List<String> stages = DataSummaryService.FUNNEL_STAGES;
        Map<String, Long> byStage = leads.stream().collect(Collectors.groupingBy(BackofficeStore.HistoricalLead::stageReached, Collectors.counting()));
        List<FunnelStage> out = new ArrayList<>();
        long atLeast = 0;
        for (int i = stages.size() - 1; i >= 0; i--) {
            atLeast += byStage.getOrDefault(stages.get(i), 0L);
            out.add(0, new FunnelStage(stages.get(i), atLeast));
        }
        return out;
    }

    static List<SegmentEconomics> segments(List<BackofficeStore.HistoricalLead> leads) {
        return grouped(leads, BackofficeStore.HistoricalLead::segment).entrySet().stream().map(e -> {
            List<BackofficeStore.HistoricalLead> group = e.getValue();
            List<BackofficeStore.HistoricalLead> active = activated(group);
            long cac = cac(group, active);
            long monthly = Math.round(active.stream().filter(l -> l.monthlyBundleInr() != null).mapToInt(BackofficeStore.HistoricalLead::monthlyBundleInr).average().orElse(0));
            List<Boolean> churn = active.stream().map(BackofficeStore.HistoricalLead::churned).filter(c -> c != null).toList();
            double churned = churn.isEmpty() ? 0 : round(churn.stream().filter(c -> c).count() * 100.0 / churn.size());
            return new SegmentEconomics(e.getKey(), group.size(), active.size(), rate(active, group), cac, monthly, monthly == 0 ? 0 : round((double) cac / monthly), churned);
        }).sorted(Comparator.comparing(SegmentEconomics::leads).reversed()).toList();
    }

    static List<SourceEconomics> sources(List<BackofficeStore.HistoricalLead> leads) {
        return grouped(leads, BackofficeStore.HistoricalLead::leadSource).entrySet().stream().map(e -> {
            List<BackofficeStore.HistoricalLead> active = activated(e.getValue());
            return new SourceEconomics(e.getKey(), e.getValue().size(), active.size(), rate(active, e.getValue()), cac(e.getValue(), active));
        }).sorted(Comparator.comparing(SourceEconomics::conversion).reversed()).toList();
    }

    private ModelCard modelCard() {
        JsonNode m = scorer.modelInfo();
        JsonNode metrics = m.get("metrics");
        List<Map<String, Object>> lift = json.convertValue(metrics.get("liftTable"), new TypeReference<>() {
        });
        return new ModelCard(m.get("variant").asText(), m.get("variantName").asText(), metrics.get("cv").get("auc").asDouble(),
                metrics.get("cv").get("top20Capture").asDouble(), metrics.get("temporal").get("auc").asDouble(),
                metrics.get("temporal").get("top20Capture").asDouble(), lift);
    }

    private static Map<String, List<BackofficeStore.HistoricalLead>> grouped(List<BackofficeStore.HistoricalLead> leads, Function<BackofficeStore.HistoricalLead, String> key) {
        return leads.stream().collect(Collectors.groupingBy(key));
    }

    private static List<BackofficeStore.HistoricalLead> activated(List<BackofficeStore.HistoricalLead> group) {
        return group.stream().filter(l -> "Activated".equals(l.outcome())).toList();
    }

    /** Everything spent on the group, including leads that never activated, per activation. */
    private static long cac(List<BackofficeStore.HistoricalLead> group, List<BackofficeStore.HistoricalLead> active) {
        return active.isEmpty() ? 0 : Math.round((double) group.stream().mapToLong(BackofficeStore.HistoricalLead::acquisitionCostInr).sum() / active.size());
    }

    private static double rate(List<?> part, List<?> whole) {
        return round(part.size() * 100.0 / whole.size());
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
