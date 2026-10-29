package com.udyam.rules.profile;

import com.udyam.rules.eligibility.EligibilityContext;
import java.util.List;
import java.util.Set;

/**
 * A BusinessProfile after resolution: tier and state from the city, segment from the business type,
 * GSTIN checked against the registry. This is what every rule runs on.
 *
 * @param segmentSource GIVEN, BUSINESS_TYPE or INFERRED
 * @param gstin         normalized GSTIN as given (null if none)
 * @param gstinAutofill registry details when the GSTIN was found (any status), else null
 */
public record ResolvedProfile(
        String businessType,
        Segment segment,
        String segmentCode,
        String segmentSource,
        String city,
        int cityTier,
        String cityState,
        int employees,
        int sites,
        GstinStatus gstinStatus,
        String gstin,
        String gstinRegistryStatus,
        List<String> gstinErrors,
        GstinAutofill gstinAutofill,
        String currentInternet,
        Integer monthlyTelecomSpendInr,
        boolean fibreUnavailable,
        Set<Need> needs,
        Integer officeUsers,
        Integer mobileLines,
        Integer cameras,
        Integer vehicles,
        Integer machines,
        List<String> warnings) implements EligibilityContext {
}
