package com.udyam.rules.eligibility;

import com.udyam.rules.profile.GstinStatus;

/** The facts the catalogue rules need about a business (implemented by ResolvedProfile and by audit cases). */
public interface EligibilityContext {

    int cityTier();

    int employees();

    int sites();

    GstinStatus gstinStatus();

    /** Registry status text (Active/Cancelled/Suspended) when known, else null. */
    String gstinRegistryStatus();
}
