package com.udyam.kyc.checks;

/**
 * The 17 KYC checks. fixable = the customer can clear a FAIL by re-uploading or changing the order;
 * a non-fixable blocking FAIL (tampering, cancelled GSTIN, identity mismatch) rejects the KYC.
 * Soft results (WARN) never block.
 */
public enum CheckCode {
    C01("ALL_DOCUMENTS_PRESENT", true),
    C02("IMAGES_READABLE", true),
    C03("DOC_TYPES_MATCH", true),
    C04("GSTIN_VALID", false),
    C05("GSTIN_IN_REGISTRY", false),
    C06("GSTIN_ACTIVE", false),
    C07("GST_DOC_MATCHES_REGISTRY", false),
    C08("UDYAM_NUMBER_VALID", true),
    C09("UDYAM_IN_REGISTRY", false),
    C10("BUSINESS_NAME_CONSISTENT", false),
    C11("PROPRIETOR_IS_SIGNATORY", false),
    C12("ADDRESS_CONSISTENT", false),
    C13("STATE_CONSISTENT", false),
    C14("ADDRESS_PROOF_RECENT", true),
    C15("ID_VALID", true),
    C16("UDYAM_DETAILS_SOFT", true),
    C17("ORDER_PROFILE_MATCH", true);

    private final String checkName;
    private final boolean fixable;

    CheckCode(String checkName, boolean fixable) {
        this.checkName = checkName;
        this.fixable = fixable;
    }

    public String checkName() {
        return checkName;
    }

    public boolean fixable() {
        return fixable;
    }
}
