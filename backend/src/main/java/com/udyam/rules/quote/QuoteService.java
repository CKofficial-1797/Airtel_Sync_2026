package com.udyam.rules.quote;

import com.udyam.common.config.AppProperties;
import com.udyam.common.errors.ApiException;
import com.udyam.rules.data.Catalogue;
import com.udyam.rules.data.CatalogueProduct;
import com.udyam.rules.data.RulesDataProvider;
import com.udyam.rules.eligibility.BundleResolution;
import com.udyam.rules.eligibility.EligibilityResult;
import com.udyam.rules.eligibility.EligibilityService;
import com.udyam.rules.profile.BusinessProfile;
import com.udyam.rules.profile.ProfileResolver;
import com.udyam.rules.profile.ResolvedProfile;
import com.udyam.rules.recommend.QuantityCalculator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Prices a bundle after fixed-point eligibility. Integer rupees; catalogue prices only. */
@Service
public class QuoteService {

    public static final String PRICE_NOTE = "Prices as per Accel Business catalogue";

    private final ProfileResolver resolver;
    private final EligibilityService eligibility;
    private final PriceCalculator prices;
    private final QuantityCalculator quantities;
    private final RulesDataProvider data;
    private final LocalDate referenceDate;

    public QuoteService(ProfileResolver resolver, EligibilityService eligibility, PriceCalculator prices,
                        QuantityCalculator quantities, RulesDataProvider data, AppProperties app) {
        this.resolver = resolver;
        this.eligibility = eligibility;
        this.prices = prices;
        this.quantities = quantities;
        this.data = data;
        this.referenceDate = app.referenceDate();
    }

    public Quote quote(BusinessProfile profile, List<QuoteItem> items, Collection<String> removedProductIds) {
        return quote(resolver.resolve(profile), items, removedProductIds);
    }

    public Quote quote(ResolvedProfile profile, List<QuoteItem> items, Collection<String> removedProductIds) {
        Catalogue catalogue = data.get().catalogue();
        Map<String, Integer> requested = new LinkedHashMap<>();
        for (QuoteItem item : items) {
            String id = catalogue.get(item.productId()).productId();
            if (requested.put(id, item.quantity()) != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_PRODUCT", "Product " + id + " is listed twice");
            }
        }
        List<String> userRemoved = removedProductIds == null ? List.of()
                : removedProductIds.stream().map(id -> catalogue.get(id).productId())
                        .filter(id -> !requested.containsKey(id)).toList();

        BundleResolution resolution = eligibility.resolveBundle(profile, requested.keySet(), userRemoved);

        List<Quote.Line> lines = new ArrayList<>();
        int monthly = 0;
        int oneTime = 0;
        int contract = 0;
        int install = 0;
        boolean conditional = false;
        for (EligibilityResult r : resolution.kept()) {
            CatalogueProduct p = catalogue.get(r.productId());
            int qty = requested.get(p.productId());
            int unitPrice = prices.unitPrice(p);
            Quote.Line line = new Quote.Line(p.productId(), p.productName(), p.unit(), qty, unitPrice, unitPrice * qty,
                    p.setupFeeInr() * qty, p.minContractMonths(), p.installDays(), r.conditional());
            lines.add(line);
            monthly += line.monthlyTotalInr();
            oneTime += line.setupFeeInr();
            contract = Math.max(contract, p.minContractMonths());
            install = Math.max(install, p.installDays());
            conditional |= r.conditional();
        }

        List<Quote.RemovedItem> removed = resolution.removed().stream()
                .map(r -> new Quote.RemovedItem(r.productId(), r.productName(), requested.get(r.productId()), r.reasons()))
                .toList();

        List<String> keptIds = resolution.keptIds();
        List<Quote.AddOn> addOns = new ArrayList<>();
        List<EligibilityResult> ineligible = new ArrayList<>();
        for (CatalogueProduct p : catalogue.all()) {
            if (requested.containsKey(p.productId())) {
                continue;
            }
            EligibilityResult r = eligibility.check(profile, p.productId(), keptIds);
            if (r.eligible()) {
                addOns.add(new Quote.AddOn(p.productId(), p.productName(), p.unit(), prices.unitPrice(p),
                        quantities.quantity(profile, p), p.setupFeeInr(), r.conditional()));
            } else {
                ineligible.add(r);
            }
        }

        Quote.SpendComparison comparison = profile.monthlyTelecomSpendInr() == null ? null
                : new Quote.SpendComparison(profile.monthlyTelecomSpendInr(), monthly,
                        monthly - profile.monthlyTelecomSpendInr());

        List<String> notes = new ArrayList<>();
        notes.add(PRICE_NOTE + (prices.mode() == PricingMode.MIN
                ? " (digital price = catalogue minimum)" : " (mid-point of the catalogue range)"));
        notes.add("No discounts or taxes applied");
        if (!lines.isEmpty()) {
            notes.add("Estimated activation assumes installation starts on " + referenceDate
                    + "; it will be updated once KYC is complete");
        }
        if (conditional) {
            notes.add(EligibilityService.KYC_CONDITION);
        }

        LocalDate activation = lines.isEmpty() ? null : referenceDate.plusDays(install);
        return new Quote(prices.mode(), List.copyOf(lines), monthly, oneTime, contract, install, activation, conditional,
                removed, List.copyOf(addOns), List.copyOf(ineligible), comparison, List.copyOf(notes));
    }
}
