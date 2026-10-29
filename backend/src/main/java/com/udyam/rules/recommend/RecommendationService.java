package com.udyam.rules.recommend;

import com.udyam.rules.data.Catalogue;
import com.udyam.rules.data.CatalogueProduct;
import com.udyam.rules.data.RulesDataProvider;
import com.udyam.rules.eligibility.BundleResolution;
import com.udyam.rules.eligibility.EligibilityResult;
import com.udyam.rules.eligibility.EligibilityService;
import com.udyam.rules.eligibility.Reason;
import com.udyam.rules.profile.BusinessProfile;
import com.udyam.rules.profile.Need;
import com.udyam.rules.profile.ProfileResolver;
import com.udyam.rules.profile.ResolvedProfile;
import com.udyam.rules.quote.PriceCalculator;
import com.udyam.rules.quote.Quote;
import com.udyam.rules.quote.QuoteItem;
import com.udyam.rules.quote.QuoteService;
import com.udyam.rules.recommend.Recommendation.Line;
import com.udyam.rules.recommend.Recommendation.Role;
import com.udyam.rules.recommend.RecommendationProperties.SegmentTemplate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Builds a recommendation from the segment template in recommendation-templates.yml.
 * Every product goes through EligibilityService before it is included or offered, so an
 * ineligible product can never be recommended.
 */
@Service
public class RecommendationService {

    private final ProfileResolver resolver;
    private final EligibilityService eligibility;
    private final QuantityCalculator quantities;
    private final PriceCalculator prices;
    private final QuoteService quotes;
    private final RulesDataProvider data;
    private final RecommendationProperties config;

    public RecommendationService(ProfileResolver resolver, EligibilityService eligibility,
                                 QuantityCalculator quantities, PriceCalculator prices, QuoteService quotes,
                                 RulesDataProvider data, RecommendationProperties config) {
        this.resolver = resolver;
        this.eligibility = eligibility;
        this.quantities = quantities;
        this.prices = prices;
        this.quotes = quotes;
        this.data = data;
        this.config = config;
    }

    public Recommendation recommend(BusinessProfile profile) {
        return recommend(resolver.resolve(profile));
    }

    public Recommendation recommend(ResolvedProfile rp) {
        Catalogue catalogue = data.get().catalogue();
        SegmentTemplate template = config.template(rp.segment());
        List<String> notes = new ArrayList<>();
        Map<String, EligibilityResult> excluded = new LinkedHashMap<>();
        Map<String, Role> bundle = new LinkedHashMap<>();

        String primary = choosePrimary(rp, template, notes, excluded, catalogue);
        bundle.put(primary, Role.PRIMARY);
        for (String id : template.included()) {
            include(rp, id, Role.INCLUDED, bundle, excluded);
        }
        for (Need need : rp.needs()) {
            for (String id : config.productsFor(need)) {
                include(rp, id, Role.NEED, bundle, excluded);
            }
        }

        // Safety net: the bundle must already be a fixed point.
        BundleResolution resolution = eligibility.resolveBundle(rp, bundle.keySet());
        for (EligibilityResult r : resolution.removed()) {
            bundle.remove(r.productId());
            excluded.put(r.productId(), r);
        }

        List<Line> included = new ArrayList<>();
        for (Map.Entry<String, Role> e : bundle.entrySet()) {
            EligibilityResult r = resolution.result(e.getKey());
            included.add(line(rp, catalogue.get(e.getKey()), e.getValue(), null, r.conditional()));
        }

        List<Line> addOns = new ArrayList<>();
        for (String id : template.optional()) {
            if (bundle.containsKey(id)) {
                continue;
            }
            EligibilityResult r = eligibility.check(rp, id, bundle.keySet());
            if (r.eligible()) {
                addOns.add(line(rp, catalogue.get(id), Role.ADD_ON, null, r.conditional()));
            } else {
                excluded.putIfAbsent(id, r);
            }
        }
        for (String id : template.upgrades()) {
            if (bundle.containsKey(id) || (rp.fibreUnavailable() && config.isFibre(id))) {
                continue;
            }
            Set<String> swapped = new LinkedHashSet<>(bundle.keySet());
            swapped.remove(primary);
            swapped.add(id);
            BundleResolution sr = eligibility.resolveBundle(rp, swapped);
            EligibilityResult r = sr.result(id);
            if (r.eligible() && sr.removed().isEmpty()) {
                addOns.add(line(rp, catalogue.get(id), Role.UPGRADE, primary, r.conditional()));
            } else if (!r.eligible()) {
                excluded.putIfAbsent(id, r);
            }
        }

        bundle.keySet().forEach(excluded::remove);
        addOns.forEach(a -> excluded.remove(a.productId()));

        List<QuoteItem> items = included.stream().map(l -> new QuoteItem(l.productId(), l.quantity())).toList();
        Quote quote = quotes.quote(rp, items, List.of());
        return new Recommendation(rp, rp.segment(), List.copyOf(included), List.copyOf(addOns),
                List.copyOf(excluded.values()), List.copyOf(notes), quote);
    }

    /** Template primary (or its no-fibre variant), then down the fallback chain until one is eligible. */
    private String choosePrimary(ResolvedProfile rp, SegmentTemplate t, List<String> notes,
                                 Map<String, EligibilityResult> excluded, Catalogue catalogue) {
        String start = rp.fibreUnavailable() && t.primaryIfNoFibre() != null ? t.primaryIfNoFibre() : t.primary();
        List<String> candidates = new ArrayList<>();
        candidates.add(start);
        List<String> chain = config.fallbackChain();
        int idx = chain.indexOf(start);
        candidates.addAll(idx >= 0 ? chain.subList(idx + 1, chain.size()) : chain);

        for (String id : new LinkedHashSet<>(candidates)) {
            String name = catalogue.name(id);
            if (rp.fibreUnavailable() && config.isFibre(id)) {
                notes.add(name + " not suggested: fibre is not available at your location");
                continue;
            }
            EligibilityResult r = eligibility.check(rp, id, List.of());
            if (r.eligible()) {
                if (!id.equals(start)) {
                    notes.add("Recommended " + name + " for connectivity instead");
                }
                return id;
            }
            excluded.put(id, r);
            notes.add(name + " not suggested: "
                    + r.reasons().stream().map(Reason::message).collect(Collectors.joining("; ")));
        }
        throw new IllegalStateException("No eligible connectivity product in the fallback chain");
    }

    private void include(ResolvedProfile rp, String id, Role role, Map<String, Role> bundle,
                         Map<String, EligibilityResult> excluded) {
        if (bundle.containsKey(id)) {
            return;
        }
        EligibilityResult r = eligibility.check(rp, id, bundle.keySet());
        if (r.eligible()) {
            bundle.put(id, role);
            excluded.remove(id);
        } else {
            excluded.putIfAbsent(id, r);
        }
    }

    private Line line(ResolvedProfile rp, CatalogueProduct p, Role role, String replaces, boolean conditional) {
        int qty = quantities.quantity(rp, p);
        int unitPrice = prices.unitPrice(p);
        return new Line(p.productId(), p.productName(), p.category(), p.unit(), qty, unitPrice, unitPrice * qty,
                p.setupFeeInr() * qty, role, replaces, why(rp, p, qty), conditional);
    }

    private String why(ResolvedProfile rp, CatalogueProduct p, int qty) {
        String text = config.whyText(p.productId());
        if (text == null) {
            text = "{productName} for your business";
        }
        return text.replace("{quantity}", String.valueOf(qty))
                .replace("{employees}", String.valueOf(rp.employees()))
                .replace("{sites}", String.valueOf(rp.sites()))
                .replace("{productName}", p.productName());
    }
}
