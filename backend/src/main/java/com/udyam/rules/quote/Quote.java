package com.udyam.rules.quote;

import com.udyam.rules.eligibility.EligibilityResult;
import com.udyam.rules.eligibility.Reason;
import java.time.LocalDate;
import java.util.List;

/**
 * One clear monthly quote. All money is integer rupees, straight from the catalogue (no discounts, no taxes).
 *
 * @param removed            requested items dropped by the rules (never silently)
 * @param availableAddOns    other products eligible for this profile + bundle (UI toggles)
 * @param ineligibleProducts the remaining products with reasons
 * @param comparison         present only when monthlyTelecomSpendInr was given
 */
public record Quote(
        PricingMode pricingMode,
        List<Line> lines,
        int monthlyTotalInr,
        int oneTimeTotalInr,
        int contractMonths,
        int maxInstallDays,
        LocalDate estimatedActivationDate,
        boolean conditional,
        List<RemovedItem> removed,
        List<AddOn> availableAddOns,
        List<EligibilityResult> ineligibleProducts,
        SpendComparison comparison,
        List<String> notes) {

    public record Line(String productId, String productName, String unit, int quantity, int unitPriceInr,
                       int monthlyTotalInr, int setupFeeInr, int minContractMonths, int installDays,
                       boolean conditional) {
    }

    public record RemovedItem(String productId, String productName, int quantity, List<Reason> reasons) {
    }

    public record AddOn(String productId, String productName, String unit, int unitPriceInr, int defaultQuantity,
                        int setupFeePerUnitInr, boolean conditional) {
    }

    /** Facts only: differenceInr = newMonthlyInr - currentSpendInr. */
    public record SpendComparison(int currentSpendInr, int newMonthlyInr, int differenceInr) {
    }
}
