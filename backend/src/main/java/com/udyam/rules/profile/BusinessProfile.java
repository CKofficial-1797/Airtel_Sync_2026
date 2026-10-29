package com.udyam.rules.profile;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

/**
 * What the advisor collects from the owner. Required: employees, sites, and city or cityTier.
 * Everything else is optional; segment is derived from businessType when absent.
 */
public record BusinessProfile(
        String businessType,
        String segment,
        String city,
        @Min(1) @Max(2) Integer cityTier,
        @NotNull @Min(1) @Max(100000) Integer employees,
        @NotNull @Min(1) @Max(500) Integer sites,
        String gstin,
        Boolean gstinDeclared,
        String currentInternet,
        @Min(0) Integer monthlyTelecomSpendInr,
        Boolean fibreUnavailable,
        Set<Need> needs,
        @Min(1) @Max(500) Integer officeUsers,
        @Min(1) @Max(500) Integer mobileLines,
        @Min(1) @Max(500) Integer cameras,
        @Min(1) @Max(500) Integer vehicles,
        @Min(1) @Max(500) Integer machines) {

    /** Convenience for code and tests: the minimum a profile needs. */
    public static BusinessProfile of(String businessType, int cityTier, int employees, int sites, String gstin) {
        return new BusinessProfile(businessType, null, null, cityTier, employees, sites, gstin, null, null, null,
                null, null, null, null, null, null, null);
    }
}
