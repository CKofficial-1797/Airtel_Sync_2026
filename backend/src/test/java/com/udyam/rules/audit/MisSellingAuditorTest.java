package com.udyam.rules.audit;

import static com.udyam.rules.RulesFixture.AUDITOR;
import static org.assertj.core.api.Assertions.assertThat;

import com.udyam.TestData;
import com.udyam.dataload.BundleItem;
import com.udyam.dataload.CsvParsing;
import com.udyam.registry.GstinDetails;
import com.udyam.rules.RulesFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MisSellingAuditorTest {

    private static List<SoldBundle> bundles;

    @BeforeAll
    static void loadActivatedBundles() {
        bundles = CsvParsing.readAll(TestData.LEADS_CSV).stream()
                .filter(r -> "Activated".equals(r.get("outcome")))
                .map(r -> new SoldBundle(r.get("lead_id"), Integer.parseInt(r.get("city_tier")),
                        Integer.parseInt(r.get("employee_count")), Integer.parseInt(r.get("num_sites")),
                        "Y".equals(r.get("has_gstin")),
                        RulesFixture.REGISTRY.find(r.get("gstin")).map(GstinDetails::status).orElse(null),
                        CsvParsing.parseBundle(r.get("bundle_products")).stream().map(BundleItem::productId).toList()))
                .toList();
    }

    private static Map<String, Integer> breakdown(MisSellingReport.Variant v) {
        return v.violationsByProductAndReason().stream()
                .collect(Collectors.toMap(c -> c.productId() + " " + c.reason(), MisSellingReport.ViolationCount::count));
    }

    @Test
    void hasGstinFlagVariantMatchesTheExpectedNumbers() {
        MisSellingReport.Variant v = AUDITOR.audit(bundles, GstinSemantics.HAS_GSTIN_FLAG);
        assertThat(v.activatedChecked()).isEqualTo(368);
        assertThat(v.bundlesWithViolations()).isEqualTo(48);
        assertThat(v.totalViolations()).isEqualTo(53);
        assertThat(v.misSoldPercent()).isEqualByComparingTo(new BigDecimal("13.0"));
        assertThat(breakdown(v)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "P03 TIER_NOT_AVAILABLE", 24,
                "P03 BELOW_MIN_EMPLOYEES", 7,
                "P03 GSTIN_REQUIRED", 3,
                "P06 BELOW_MIN_EMPLOYEES", 1,
                "P10 GSTIN_REQUIRED", 1,
                "P11 DEPENDENCY_MISSING", 9,
                "P12 GSTIN_REQUIRED", 2,
                "P14 GSTIN_REQUIRED", 1,
                "P15 GSTIN_REQUIRED", 5));
        assertThat(v.sampleLeadIds()).hasSize(10);
    }

    @Test
    void registryActiveVariantIsHigher() {
        MisSellingReport.Variant v = AUDITOR.audit(bundles, GstinSemantics.REGISTRY_ACTIVE);
        assertThat(v.bundlesWithViolations()).isEqualTo(56);
        assertThat(v.totalViolations()).isEqualTo(63);
        assertThat(breakdown(v)).containsEntry("P15 GSTIN_INACTIVE", 4).containsEntry("P03 GSTIN_INACTIVE", 3);
    }
}
