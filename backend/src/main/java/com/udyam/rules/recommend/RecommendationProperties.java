package com.udyam.rules.recommend;

import com.udyam.rules.profile.Need;
import com.udyam.rules.profile.Segment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of recommendation-templates.yml (prefix "recommendation"). */
@ConfigurationProperties(prefix = "recommendation")
public record RecommendationProperties(
        List<String> fallbackChain,
        List<String> fibreProducts,
        Map<String, SegmentTemplate> segments,
        List<NeedMapping> needs,
        Quantities quantities,
        List<WhyText> why) {

    public RecommendationProperties {
        fallbackChain = orEmpty(fallbackChain);
        fibreProducts = orEmpty(fibreProducts);
        segments = segments == null ? Map.of() : Map.copyOf(segments);
        needs = orEmpty(needs);
        why = orEmpty(why);
    }

    public SegmentTemplate template(Segment segment) {
        SegmentTemplate t = segments.get(segment.code());
        if (t == null) {
            throw new IllegalStateException("No recommendation template for segment " + segment.code());
        }
        return t;
    }

    public List<String> productsFor(Need need) {
        return needs.stream().filter(n -> n.need() == need).findFirst().map(NeedMapping::products).orElse(List.of());
    }

    public boolean isFibre(String productId) {
        return fibreProducts.contains(productId);
    }

    public String whyText(String productId) {
        return why.stream().filter(w -> w.productId().equals(productId)).findFirst().map(WhyText::text).orElse(null);
    }

    public record SegmentTemplate(String primary, String primaryIfNoFibre, List<String> upgrades,
                                  List<String> included, List<String> optional) {
        public SegmentTemplate {
            upgrades = orEmpty(upgrades);
            included = orEmpty(included);
            optional = orEmpty(optional);
        }
    }

    public record NeedMapping(Need need, List<String> products) {
        public NeedMapping {
            products = orEmpty(products);
        }
    }

    public record Quantities(QuantityRule defaultRule, List<UnitRule> unitRules, List<ProductQuantity> products) {
        public Quantities {
            unitRules = orEmpty(unitRules);
            products = orEmpty(products);
        }
    }

    public record UnitRule(String unit, QuantityRule rule) {
    }

    /** Per-product quantity rules: an explicit profile field wins, then the segment rule, then fallback. */
    public record ProductQuantity(String productId, ProfileField profileField, Map<String, QuantityRule> segments,
                                  QuantityRule fallback) {
        public ProductQuantity {
            segments = segments == null ? Map.of() : Map.copyOf(segments);
        }
    }

    /** SITES/EMPLOYEES: max(min, ceil(factor x base)); FIXED: value. factor defaults to 1, min to 1. */
    public record QuantityRule(QuantitySource from, BigDecimal factor, Integer min, Integer value) {
    }

    public enum QuantitySource { SITES, EMPLOYEES, FIXED }

    public enum ProfileField { OFFICE_USERS, MOBILE_LINES, CAMERAS, VEHICLES, MACHINES }

    public record WhyText(String productId, String text) {
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
