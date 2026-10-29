package com.udyam.rules.data;

import com.udyam.common.errors.ApiException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/** The 25 catalogue products, ordered by product id. */
public final class Catalogue {

    private final Map<String, CatalogueProduct> byId = new LinkedHashMap<>();

    public Catalogue(Collection<CatalogueProduct> products) {
        products.stream()
                .sorted(Comparator.comparing(CatalogueProduct::productId))
                .forEach(p -> byId.put(p.productId(), p));
    }

    public List<CatalogueProduct> all() {
        return List.copyOf(byId.values());
    }

    public Optional<CatalogueProduct> find(String productId) {
        return productId == null ? Optional.empty() : Optional.ofNullable(byId.get(normalize(productId)));
    }

    /** Returns the product or throws 400 UNKNOWN_PRODUCT. */
    public CatalogueProduct get(String productId) {
        return find(productId).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_PRODUCT",
                "Unknown product: " + productId));
    }

    public String name(String productId) {
        return get(productId).productName();
    }

    public int size() {
        return byId.size();
    }

    public static String normalize(String productId) {
        return productId.trim().toUpperCase(Locale.ROOT);
    }
}
