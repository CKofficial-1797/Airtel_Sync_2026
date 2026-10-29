package com.udyam.catalogue;

import java.util.List;

/** API view of a catalogue product; array columns are exposed as JSON lists. */
public record ProductDto(
        String productId,
        String category,
        String productName,
        String description,
        String unit,
        int priceMinInrPerMonth,
        int priceMaxInrPerMonth,
        int oneTimeSetupInr,
        int minContractMonths,
        List<Integer> availableCityTiers,
        int minEmployees,
        int minSites,
        boolean requiresGstin,
        List<String> requiresOneOf,
        int typicalInstallDays,
        List<String> targetSegments) {

    public static ProductDto from(Product p) {
        return new ProductDto(
                p.getProductId(),
                p.getCategory(),
                p.getProductName(),
                p.getDescription(),
                p.getUnit(),
                p.getPriceMinInrPerMonth(),
                p.getPriceMaxInrPerMonth(),
                p.getOneTimeSetupInr(),
                p.getMinContractMonths(),
                List.of(p.getAvailableCityTiers()),
                p.getMinEmployees(),
                p.getMinSites(),
                p.getRequiresGstin(),
                List.of(p.getRequiresOneOf()),
                p.getTypicalInstallDays(),
                List.of(p.getTargetSegments()));
    }
}
