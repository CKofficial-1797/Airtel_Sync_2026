package com.udyam.leads;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Response of GET /api/admin/data-summary. */
public record DataSummary(
        RowCounts rowCounts,
        List<FunnelStage> funnel,
        long activated,
        BigDecimal avgDaysLeadToActivation,
        BigDecimal avgFollowUpCallsActivated,
        long totalAcquisitionCostInr,
        BigDecimal blendedCacInr,
        Map<String, Long> gstinStatusCounts,
        Integrity integrity) {

    public record RowCounts(long products, long gstinRegistry, long udyamRegistry, long leads, long leadBundleItems) {
    }

    /** Number of leads that reached at least this stage. */
    public record FunnelStage(String stage, long leads) {
    }

    /** Leads that claim a GSTIN/Udyam number which is missing from the mock registry (expected 0). */
    public record Integrity(long leadGstinMissingInRegistry, long leadUdyamMissingInRegistry) {
    }
}
