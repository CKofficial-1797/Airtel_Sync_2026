package com.udyam.kyc.checks;

import com.udyam.kyc.DocType;
import com.udyam.kyc.KycDocument;
import java.util.List;
import java.util.Map;

/**
 * Everything the checks look at.
 *
 * @param documents         latest document per slot (missing slots are absent)
 * @param hasOrderProfile   false for orders without a profile (specimen harness): C17 is skipped
 * @param orderGstin        GSTIN the owner gave in the advisor profile, or null
 * @param gstRequiredProducts ids of ordered products that require an active GSTIN
 */
public record KycInput(
        Map<DocType, KycDocument> documents,
        boolean hasOrderProfile,
        String orderGstin,
        List<String> gstRequiredProducts) {

    public KycDocument doc(DocType type) {
        return documents.get(type);
    }

    /** The document when it is present and usable (OK + fields), else null. */
    public KycDocument usable(DocType type) {
        KycDocument d = documents.get(type);
        return d != null && d.usable() ? d : null;
    }
}
