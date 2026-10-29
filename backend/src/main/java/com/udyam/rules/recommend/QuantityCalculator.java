package com.udyam.rules.recommend;

import com.udyam.rules.data.CatalogueProduct;
import com.udyam.rules.profile.ResolvedProfile;
import com.udyam.rules.recommend.RecommendationProperties.ProductQuantity;
import com.udyam.rules.recommend.RecommendationProperties.QuantityRule;
import com.udyam.rules.recommend.RecommendationProperties.UnitRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** Default quantity per product, driven entirely by the "quantities" section of recommendation-templates.yml. */
@Component
public class QuantityCalculator {

    public static final int MAX_QUANTITY = 500;

    private final RecommendationProperties config;

    public QuantityCalculator(RecommendationProperties config) {
        this.config = config;
    }

    public int quantity(ResolvedProfile profile, CatalogueProduct product) {
        ProductQuantity pq = config.quantities().products().stream()
                .filter(q -> q.productId().equals(product.productId()))
                .findFirst().orElse(null);
        if (pq != null) {
            Integer explicit = explicitValue(profile, pq.profileField());
            if (explicit != null) {
                return clamp(explicit);
            }
            QuantityRule rule = pq.segments().get(profile.segment().code());
            if (rule == null) {
                rule = pq.fallback();
            }
            if (rule != null) {
                return apply(rule, profile);
            }
        }
        for (UnitRule u : config.quantities().unitRules()) {
            if (u.unit().equalsIgnoreCase(product.unit())) {
                return apply(u.rule(), profile);
            }
        }
        QuantityRule fallback = config.quantities().defaultRule();
        return fallback == null ? 1 : apply(fallback, profile);
    }

    static int apply(QuantityRule rule, ResolvedProfile profile) {
        if (rule.from() == RecommendationProperties.QuantitySource.FIXED) {
            return clamp(rule.value() == null ? 1 : rule.value());
        }
        int base = rule.from() == RecommendationProperties.QuantitySource.SITES ? profile.sites() : profile.employees();
        BigDecimal factor = rule.factor() == null ? BigDecimal.ONE : rule.factor();
        int q = factor.multiply(BigDecimal.valueOf(base)).setScale(0, RoundingMode.CEILING).intValueExact();
        return clamp(Math.max(rule.min() == null ? 1 : rule.min(), q));
    }

    private static Integer explicitValue(ResolvedProfile p, RecommendationProperties.ProfileField field) {
        if (field == null) {
            return null;
        }
        return switch (field) {
            case OFFICE_USERS -> p.officeUsers();
            case MOBILE_LINES -> p.mobileLines();
            case CAMERAS -> p.cameras();
            case VEHICLES -> p.vehicles();
            case MACHINES -> p.machines();
        };
    }

    private static int clamp(int q) {
        return Math.max(1, Math.min(MAX_QUANTITY, q));
    }
}
