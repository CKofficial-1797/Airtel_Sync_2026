package com.udyam.rules.profile;

/** How the rules engine treats the business's GSTIN. Only VERIFIED_ACTIVE (and, conditionally, DECLARED_UNVERIFIED) unlocks GST-required products. */
public enum GstinStatus {
    /** Valid format + checksum, found in the registry, status Active. */
    VERIFIED_ACTIVE,
    /** Found in the registry but Cancelled or Suspended: counts as no GSTIN. */
    INACTIVE,
    /** Given but invalid or not in the registry: counts as no GSTIN. */
    INVALID,
    /** Owner says they have one but has not typed it: GST products allowed conditionally (verified at KYC). */
    DECLARED_UNVERIFIED,
    /** No GSTIN. */
    NONE
}
