package com.udyam.rules;

import com.udyam.TestData;
import com.udyam.common.config.AppProperties;
import com.udyam.registry.CsvGstinRegistryLookup;
import com.udyam.registry.GstinCorrectionService;
import com.udyam.registry.GstinVerificationService;
import com.udyam.rules.audit.MisSellingAuditor;
import com.udyam.rules.data.RulesData;
import com.udyam.rules.data.RulesDataProvider;
import com.udyam.rules.eligibility.EligibilityService;
import com.udyam.rules.profile.BusinessProfile;
import com.udyam.rules.profile.Need;
import com.udyam.rules.profile.ProfileResolver;
import com.udyam.rules.profile.ResolvedProfile;
import com.udyam.rules.quote.PriceCalculator;
import com.udyam.rules.quote.PricingMode;
import com.udyam.rules.quote.PricingProperties;
import com.udyam.rules.quote.QuoteService;
import com.udyam.rules.recommend.QuantityCalculator;
import com.udyam.rules.recommend.RecommendationProperties;
import com.udyam.rules.recommend.RecommendationService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/** The whole rules engine wired by hand from the CSVs and the real template YAML. No Spring context, no DB. */
public final class RulesFixture {

    public static final String ACTIVE_GSTIN = "32TTNXB7384T1ZW";
    public static final String CANCELLED_GSTIN = "37IHKXV5167L1ZS";
    public static final String SUSPENDED_GSTIN = "23QYBXS1811Q1ZD";
    public static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 7, 15);

    public static final RulesData DATA = RulesData.fromCsv(TestData.DATA_DIR);
    public static final RulesDataProvider PROVIDER = () -> DATA;
    public static final CsvGstinRegistryLookup REGISTRY = CsvGstinRegistryLookup.fromCsv(TestData.GSTIN_CSV);
    public static final GstinVerificationService GSTINS =
            new GstinVerificationService(REGISTRY, new GstinCorrectionService(REGISTRY));
    public static final ProfileResolver RESOLVER = new ProfileResolver(PROVIDER, GSTINS);
    public static final EligibilityService ELIGIBILITY = new EligibilityService(PROVIDER);
    public static final RecommendationProperties TEMPLATES = loadTemplates();
    public static final QuantityCalculator QUANTITIES = new QuantityCalculator(TEMPLATES);
    public static final PriceCalculator MIN_PRICES = new PriceCalculator(new PricingProperties(PricingMode.MIN));
    public static final AppProperties APP = new AppProperties("../data", REFERENCE_DATE, null);
    public static final QuoteService QUOTES = quoteService(MIN_PRICES);
    public static final RecommendationService RECOMMENDER = new RecommendationService(RESOLVER, ELIGIBILITY,
            QUANTITIES, MIN_PRICES, QUOTES, PROVIDER, TEMPLATES);
    public static final MisSellingAuditor AUDITOR = new MisSellingAuditor(ELIGIBILITY, PROVIDER);

    private RulesFixture() {
    }

    public static QuoteService quoteService(PriceCalculator prices) {
        return new QuoteService(RESOLVER, ELIGIBILITY, prices, QUANTITIES, PROVIDER, APP);
    }

    /** Profile builder for tests; gstinMode = ACTIVE | CANCELLED | SUSPENDED | DECLARED | INVALID | NONE. */
    public static BusinessProfile profile(String businessType, int tier, int employees, int sites, String gstinMode) {
        return profile(businessType, tier, employees, sites, gstinMode, false, Set.of());
    }

    public static BusinessProfile profile(String businessType, int tier, int employees, int sites, String gstinMode,
                                          boolean fibreUnavailable, Set<Need> needs) {
        String gstin = switch (gstinMode) {
            case "ACTIVE" -> ACTIVE_GSTIN;
            case "CANCELLED" -> CANCELLED_GSTIN;
            case "SUSPENDED" -> SUSPENDED_GSTIN;
            case "INVALID" -> "ABC";
            default -> null;
        };
        Boolean declared = "DECLARED".equals(gstinMode) ? Boolean.TRUE : null;
        return new BusinessProfile(businessType, null, null, tier, employees, sites, gstin, declared, null, null,
                fibreUnavailable, needs, null, null, null, null, null);
    }

    public static ResolvedProfile resolved(int tier, int employees, int sites, String gstinMode) {
        return RESOLVER.resolve(profile(null, tier, employees, sites, gstinMode));
    }

    public static List<String> ids(String semicolonList) {
        return semicolonList == null || semicolonList.isBlank() ? List.of() : List.of(semicolonList.split(";"));
    }

    private static RecommendationProperties loadTemplates() {
        try {
            var sources = new YamlPropertySourceLoader()
                    .load("recommendation-templates", new ClassPathResource("recommendation-templates.yml"));
            return new Binder(ConfigurationPropertySources.from(sources))
                    .bind("recommendation", RecommendationProperties.class).get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
