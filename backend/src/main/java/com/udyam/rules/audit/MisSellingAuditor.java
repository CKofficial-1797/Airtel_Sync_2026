package com.udyam.rules.audit;

import com.udyam.registry.GstinDetails;
import com.udyam.rules.data.RulesDataProvider;
import com.udyam.rules.eligibility.EligibilityContext;
import com.udyam.rules.eligibility.EligibilityService;
import com.udyam.rules.eligibility.Reason;
import com.udyam.rules.profile.GstinStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Re-checks what sales actually sold against the catalogue rules. Each product's own rules are checked
 * against the bundle AS SOLD (a dependency counts if any listed product is in the sold bundle; no cascade).
 * Pure logic: the caller supplies the sold bundles (DB in the app, CSV in tests).
 */
@Component
public class MisSellingAuditor {

    private static final int SAMPLE_SIZE = 10;

    private final EligibilityService eligibility;
    private final RulesDataProvider data;

    public MisSellingAuditor(EligibilityService eligibility, RulesDataProvider data) {
        this.eligibility = eligibility;
        this.data = data;
    }

    public MisSellingReport report(List<SoldBundle> bundles) {
        return new MisSellingReport(audit(bundles, GstinSemantics.HAS_GSTIN_FLAG),
                audit(bundles, GstinSemantics.REGISTRY_ACTIVE));
    }

    public MisSellingReport.Variant audit(List<SoldBundle> bundles, GstinSemantics semantics) {
        Map<String, Integer> counts = new TreeMap<>();
        List<String> samples = new ArrayList<>();
        int withViolations = 0;
        int total = 0;
        for (SoldBundle b : bundles) {
            AuditContext ctx = new AuditContext(b.cityTier(), b.employees(), b.sites(), status(b, semantics),
                    b.registryStatus());
            int violations = 0;
            for (String productId : b.productIds()) {
                for (Reason r : eligibility.auditReasons(ctx, productId, b.productIds())) {
                    counts.merge(productId + "|" + r.code().name(), 1, Integer::sum);
                    violations++;
                }
            }
            if (violations > 0) {
                withViolations++;
                total += violations;
                if (samples.size() < SAMPLE_SIZE) {
                    samples.add(b.leadId());
                }
            }
        }

        List<MisSellingReport.ViolationCount> breakdown = counts.entrySet().stream().map(e -> {
            String[] parts = e.getKey().split("\\|");
            return new MisSellingReport.ViolationCount(parts[0], data.get().catalogue().name(parts[0]), parts[1],
                    e.getValue());
        }).toList();
        BigDecimal percent = bundles.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(withViolations * 100L).divide(BigDecimal.valueOf(bundles.size()), 1,
                        RoundingMode.HALF_UP);
        String description = semantics == GstinSemantics.HAS_GSTIN_FLAG
                ? "GSTIN counted when smb_lead.has_gstin = Y (as recorded by sales)"
                : "GSTIN counted only when the registry status is Active";
        return new MisSellingReport.Variant(semantics, description, bundles.size(), withViolations, percent, total,
                breakdown, List.copyOf(samples));
    }

    private static GstinStatus status(SoldBundle b, GstinSemantics semantics) {
        if (!b.hasGstin()) {
            return GstinStatus.NONE;
        }
        if (semantics == GstinSemantics.HAS_GSTIN_FLAG) {
            return GstinStatus.VERIFIED_ACTIVE;
        }
        if (b.registryStatus() == null) {
            return GstinStatus.INVALID;
        }
        return GstinDetails.ACTIVE.equals(b.registryStatus()) ? GstinStatus.VERIFIED_ACTIVE : GstinStatus.INACTIVE;
    }

    private record AuditContext(int cityTier, int employees, int sites, GstinStatus gstinStatus,
                                String gstinRegistryStatus) implements EligibilityContext {
    }
}
