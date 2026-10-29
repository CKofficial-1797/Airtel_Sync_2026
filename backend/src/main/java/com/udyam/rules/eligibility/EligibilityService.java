package com.udyam.rules.eligibility;

import com.udyam.rules.data.Catalogue;
import com.udyam.rules.data.CatalogueProduct;
import com.udyam.rules.data.RulesDataProvider;
import com.udyam.rules.profile.GstinStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The catalogue eligibility rules (data/README.md), implemented exactly:
 * city tier, min employees, min sites (inclusive), active GSTIN, and requires_one_of in the SAME bundle.
 * Bundles are resolved to a fixed point: removing a product also removes products that depended on it.
 * Pure in-memory logic.
 */
@Service
public class EligibilityService {

    public static final String KYC_CONDITION = "Active GSTIN will be verified at KYC";

    private final RulesDataProvider data;

    public EligibilityService(RulesDataProvider data) {
        this.data = data;
    }

    /** All failed rules for one product, ignoring dependencies. */
    public List<Reason> ruleReasons(EligibilityContext ctx, CatalogueProduct p) {
        List<Reason> reasons = new ArrayList<>();
        String name = p.productName();
        if (!p.cityTiers().contains(ctx.cityTier())) {
            reasons.add(new Reason(ReasonCode.TIER_NOT_AVAILABLE,
                    name + " is not available in tier-" + ctx.cityTier() + " cities"));
        }
        if (ctx.employees() < p.minEmployees()) {
            reasons.add(new Reason(ReasonCode.BELOW_MIN_EMPLOYEES, name + " needs at least " + p.minEmployees()
                    + " employees (you have " + ctx.employees() + ")"));
        }
        if (ctx.sites() < p.minSites()) {
            reasons.add(new Reason(ReasonCode.BELOW_MIN_SITES, name + " needs at least " + p.minSites()
                    + " sites (you have " + ctx.sites() + ")"));
        }
        if (p.requiresGstin()) {
            switch (ctx.gstinStatus()) {
                case VERIFIED_ACTIVE, DECLARED_UNVERIFIED -> { }
                case INACTIVE -> reasons.add(new Reason(ReasonCode.GSTIN_INACTIVE,
                        name + " needs an active GSTIN; your GSTIN is " + ctx.gstinRegistryStatus()));
                case INVALID -> reasons.add(new Reason(ReasonCode.GSTIN_REQUIRED,
                        name + " needs an active GSTIN; the GSTIN provided could not be verified"));
                case NONE -> reasons.add(new Reason(ReasonCode.GSTIN_REQUIRED, name + " needs an active GSTIN"));
            }
        }
        return reasons;
    }

    public boolean isConditional(EligibilityContext ctx, CatalogueProduct p) {
        return p.requiresGstin() && ctx.gstinStatus() == GstinStatus.DECLARED_UNVERIFIED;
    }

    public BundleResolution resolveBundle(EligibilityContext ctx, Collection<String> productIds) {
        return resolveBundle(ctx, productIds, List.of());
    }

    /**
     * Fixed-point resolution.
     *
     * @param userRemovedIds products the user took out of the bundle: dependents that relied only on them
     *                       are removed with DEPENDENCY_REMOVED instead of DEPENDENCY_MISSING
     */
    public BundleResolution resolveBundle(EligibilityContext ctx, Collection<String> productIds,
                                          Collection<String> userRemovedIds) {
        Catalogue catalogue = data.get().catalogue();
        List<CatalogueProduct> bundle = productIds.stream().map(catalogue::get).distinct().toList();
        Set<String> present = new LinkedHashSet<>();
        bundle.forEach(p -> present.add(p.productId()));
        userRemovedIds.forEach(id -> present.add(catalogue.get(id).productId()));

        Set<String> kept = bundle.stream()
                .filter(p -> ruleReasons(ctx, p).isEmpty())
                .map(CatalogueProduct::productId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean changed = true;
        while (changed) {
            changed = kept.removeIf(id -> {
                CatalogueProduct p = catalogue.get(id);
                return p.hasDependencies() && p.requiresOneOf().stream().noneMatch(kept::contains);
            });
        }

        List<EligibilityResult> keptResults = new ArrayList<>();
        List<EligibilityResult> removedResults = new ArrayList<>();
        for (CatalogueProduct p : bundle) {
            List<Reason> reasons = ruleReasons(ctx, p);
            if (p.hasDependencies() && p.requiresOneOf().stream().noneMatch(kept::contains)) {
                reasons.add(dependencyReason(p, present, catalogue));
            }
            boolean eligible = kept.contains(p.productId());
            boolean conditional = eligible && isConditional(ctx, p);
            EligibilityResult r = new EligibilityResult(p.productId(), p.productName(), eligible, conditional,
                    conditional ? KYC_CONDITION : null, List.copyOf(reasons));
            (eligible ? keptResults : removedResults).add(r);
        }
        return new BundleResolution(List.copyOf(keptResults), List.copyOf(removedResults));
    }

    /** Eligibility of one product if it were added to the given bundle. */
    public EligibilityResult check(EligibilityContext ctx, String productId, Collection<String> bundleProductIds) {
        String id = data.get().catalogue().get(productId).productId();
        Set<String> ids = new LinkedHashSet<>(bundleProductIds);
        ids.add(id);
        return resolveBundle(ctx, ids).result(id);
    }

    /** Eligibility of every catalogue product relative to the given bundle. */
    public List<EligibilityResult> checkAll(EligibilityContext ctx, Collection<String> bundleProductIds) {
        return data.get().catalogue().all().stream()
                .map(p -> check(ctx, p.productId(), bundleProductIds))
                .toList();
    }

    /**
     * Audit view (no cascade): a product's own rules checked against the bundle AS SOLD; a dependency is
     * satisfied if any listed product is present in the sold bundle.
     */
    public List<Reason> auditReasons(EligibilityContext ctx, String productId, Collection<String> soldProductIds) {
        Catalogue catalogue = data.get().catalogue();
        CatalogueProduct p = catalogue.get(productId);
        List<Reason> reasons = ruleReasons(ctx, p);
        if (p.hasDependencies() && p.requiresOneOf().stream().noneMatch(soldProductIds::contains)) {
            reasons.add(dependencyReason(p, Set.of(), catalogue));
        }
        return reasons;
    }

    private static Reason dependencyReason(CatalogueProduct p, Set<String> present, Catalogue catalogue) {
        List<String> gone = p.requiresOneOf().stream().filter(present::contains).toList();
        if (gone.isEmpty()) {
            String options = p.requiresOneOf().stream().map(catalogue::name).collect(Collectors.joining(", "));
            return new Reason(ReasonCode.DEPENDENCY_MISSING,
                    p.productName() + " needs one of " + options + " in the same bundle");
        }
        String names = gone.stream().map(catalogue::name).collect(Collectors.joining(" and "));
        return new Reason(ReasonCode.DEPENDENCY_REMOVED,
                p.productName() + " removed because " + names + " was removed");
    }
}
