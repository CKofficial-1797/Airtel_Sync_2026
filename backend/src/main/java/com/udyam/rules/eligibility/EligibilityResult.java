package com.udyam.rules.eligibility;

import java.util.List;

/**
 * Eligibility of one product for a profile + bundle.
 *
 * @param conditional true when eligible only because a GSTIN was declared but not yet verified
 * @param condition   what must still be verified (null when not conditional)
 * @param reasons     ALL failed rules (empty when eligible)
 */
public record EligibilityResult(
        String productId,
        String productName,
        boolean eligible,
        boolean conditional,
        String condition,
        List<Reason> reasons) {
}
