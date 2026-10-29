package com.udyam.rules.eligibility;

public enum ReasonCode {
    TIER_NOT_AVAILABLE,
    BELOW_MIN_EMPLOYEES,
    BELOW_MIN_SITES,
    /** No GSTIN, or a GSTIN that is invalid / not in the registry. */
    GSTIN_REQUIRED,
    /** GSTIN found but Cancelled or Suspended. */
    GSTIN_INACTIVE,
    /** None of the required products is in the bundle. */
    DEPENDENCY_MISSING,
    /** A required product was in the bundle but was removed (ineligible or taken out by the user). */
    DEPENDENCY_REMOVED
}
