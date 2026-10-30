package com.udyam.backoffice;

import java.util.HashMap;
import java.util.Map;

/**
 * What we know about a lead, whichever kind it is. Null = not collected: the scorer then uses the reference level,
 * so an unanswered question neither helps nor hurts the score.
 */
public record LeadProfile(
        String leadSource,
        String businessType,
        String segment,
        String city,
        Integer tier,
        Integer employees,
        Integer sites,
        String gstinStatus,
        Boolean hasUdyam,
        Integer monthlySpendInr,
        String currentInternet,
        Integer productCount,
        Boolean gstProductInterest) {

    public static final String GST_NONE = "NONE";
    public static final String GST_ACTIVE = "ACTIVE";
    public static final String GST_INACTIVE = "INACTIVE";

    /** The model's input fields. A value it doesn't know falls back to the reference level too. */
    public Map<String, Object> modelInput() {
        Map<String, Object> input = new HashMap<>();
        input.put("lead_source", leadSource);
        input.put("segment", segment);
        input.put("city_tier", tier);
        input.put("gstin_status", gstinStatus);
        input.put("has_udyam", yesNo(hasUdyam));
        input.put("employee_count", employees);
        input.put("num_sites", sites);
        input.put("monthly_telecom_spend_inr", monthlySpendInr);
        input.put("current_internet", currentInternet);
        input.put("products_of_interest_count", productCount);
        input.put("gst_product_interest", yesNo(gstProductInterest));
        return input;
    }

    private static String yesNo(Boolean value) {
        if (value == null) {
            return null;
        }
        return value ? "Y" : "N";
    }
}
