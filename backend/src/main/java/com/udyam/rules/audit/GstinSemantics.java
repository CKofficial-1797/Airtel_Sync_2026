package com.udyam.rules.audit;

/** How the audit decides whether a sold lead had a GSTIN. */
public enum GstinSemantics {
    /** smb_lead.has_gstin = Y counts as a GSTIN (what sales recorded). */
    HAS_GSTIN_FLAG,
    /** Only a GSTIN whose registry status is Active counts (what the rules require). */
    REGISTRY_ACTIVE
}
