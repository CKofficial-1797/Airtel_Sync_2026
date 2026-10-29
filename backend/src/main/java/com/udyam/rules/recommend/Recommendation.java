package com.udyam.rules.recommend;

import com.udyam.rules.eligibility.EligibilityResult;
import com.udyam.rules.profile.ResolvedProfile;
import com.udyam.rules.profile.Segment;
import com.udyam.rules.quote.Quote;
import java.util.List;

/**
 * Response of POST /api/recommend.
 *
 * @param included       the recommended bundle (all eligible; priced in quote)
 * @param optionalAddOns eligible toggles, off by default; an UPGRADE line has replaces = the product it swaps out
 * @param excluded       products relevant to the segment or stated needs that are NOT eligible, with reasons
 * @param notes          plain-English explanation of connectivity fallbacks
 */
public record Recommendation(
        ResolvedProfile resolvedProfile,
        Segment segment,
        List<Line> included,
        List<Line> optionalAddOns,
        List<EligibilityResult> excluded,
        List<String> notes,
        Quote quote) {

    public enum Role { PRIMARY, INCLUDED, NEED, ADD_ON, UPGRADE }

    public record Line(String productId, String productName, String category, String unit, int quantity,
                       int unitPriceInr, int monthlyTotalInr, int setupFeeInr, Role role, String replaces,
                       String why, boolean conditional) {
    }
}
