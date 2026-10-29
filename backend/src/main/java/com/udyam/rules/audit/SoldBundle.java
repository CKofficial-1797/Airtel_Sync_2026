package com.udyam.rules.audit;

import java.util.List;

/** One activated lead and the bundle it was actually sold. registryStatus is null if the GSTIN is not in the registry. */
public record SoldBundle(String leadId, int cityTier, int employees, int sites, boolean hasGstin,
                         String registryStatus, List<String> productIds) {
}
