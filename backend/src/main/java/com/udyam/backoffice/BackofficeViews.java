package com.udyam.backoffice;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** The back-office API's JSON shapes. */
public final class BackofficeViews {

    private BackofficeViews() {
    }

    /** One line of the queue. status is NEW until someone changes it; assignment is null until then. */
    public record QueueRow(String kind, String ref, String displayRef, String businessName, String businessType, String segment,
                           String city, Integer tier, Integer employees, Integer sites, String gstinStatus, String stoppedAt,
                           String lastActivity, int score, double probability, String band, List<PropensityScorer.Reason> topReasons,
                           String suggestedAction, String recommendedChannel, String channelWhy, Integer quoteMonthlyInr,
                           String status, BackofficeStore.Assignment assignment) {
    }

    public record QueuePage(List<QueueRow> rows, int page, int size, long total) {
    }

    public record QuoteSummary(Integer monthlyInr, Integer oneTimeInr, Integer contractMonths, List<QuoteLine> lines) {
    }

    public record QuoteLine(String productName, int quantity) {
    }

    public record FailedCheck(String code, String messageEn, String messageHi, String fixHintEn, String fixHintHi) {
    }

    public record ChatLine(String role, String content, String createdAt) {
    }

    /** contactNote says plainly that we hold no way of reaching this lead yet. */
    public record LeadDetail(QueueRow lead, JsonNode profile, QuoteSummary quote, List<FailedCheck> failedChecks, List<ChatLine> recentMessages,
                             String contactNote, List<BackofficeStore.AssignmentEvent> history) {
    }

    public record ReasonCount(String reason, long leads) {
    }

    public record Reengagement(long processTotal, long priceTotal, List<ReasonCount> byReason, QueuePage leads) {
    }

    public record FunnelStage(String stage, long leads) {
    }

    public record SegmentEconomics(String segment, long leads, long activations, double conversion, long cacPerActivation,
                                   long avgMonthlyBundle, double paybackMonths, double churn6m) {
    }

    public record SourceEconomics(String source, long leads, long activations, double conversion, long cacPerActivation) {
    }

    public record MisSelling(int activatedChecked, int bundlesWithViolations, double misSoldPercent, int registryActiveViolations) {
    }

    public record ModelCard(String variant, String variantName, double auc, double top20Capture, double juneAuc, double juneTop20Capture,
                            List<Map<String, Object>> liftTable) {
    }

    public record Insights(List<FunnelStage> funnel, List<SegmentEconomics> segmentEconomics, List<SourceEconomics> sourceEconomics,
                           MisSelling misSelling, ModelCard model, BackofficeStore.LiveFunnel liveFunnel) {
    }
}
