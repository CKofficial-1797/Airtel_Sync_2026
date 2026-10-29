package com.udyam.rules.audit;

import java.math.BigDecimal;
import java.util.List;

/** Response of GET /api/admin/audit/mis-selling: the same rules run with two GSTIN semantics. */
public record MisSellingReport(Variant hasGstinFlag, Variant registryActive) {

    /**
     * @param misSoldPercent bundlesWithViolations / activatedChecked x 100, one decimal
     * @param sampleLeadIds  up to 10 lead ids with violations (ids only, no names)
     */
    public record Variant(
            GstinSemantics gstinSemantics,
            String description,
            int activatedChecked,
            int bundlesWithViolations,
            BigDecimal misSoldPercent,
            int totalViolations,
            List<ViolationCount> violationsByProductAndReason,
            List<String> sampleLeadIds) {
    }

    public record ViolationCount(String productId, String productName, String reason, int count) {
    }
}
