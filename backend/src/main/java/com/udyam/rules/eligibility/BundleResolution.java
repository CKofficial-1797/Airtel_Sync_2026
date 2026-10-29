package com.udyam.rules.eligibility;

import java.util.List;
import java.util.stream.Stream;

/** Fixed-point result of a bundle: what stays and what is removed (with reasons), in input order. */
public record BundleResolution(List<EligibilityResult> kept, List<EligibilityResult> removed) {

    public List<String> keptIds() {
        return kept.stream().map(EligibilityResult::productId).toList();
    }

    public EligibilityResult result(String productId) {
        return Stream.concat(kept.stream(), removed.stream())
                .filter(r -> r.productId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Product not in bundle: " + productId));
    }
}
