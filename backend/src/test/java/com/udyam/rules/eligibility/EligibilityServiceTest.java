package com.udyam.rules.eligibility;

import static com.udyam.rules.RulesFixture.ELIGIBILITY;
import static com.udyam.rules.RulesFixture.ids;
import static com.udyam.rules.RulesFixture.resolved;
import static org.assertj.core.api.Assertions.assertThat;

import com.udyam.rules.RulesFixture;
import com.udyam.rules.profile.ResolvedProfile;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EligibilityServiceTest {

    @ParameterizedTest(name = "{0} tier={1} emp={2} sites={3} gstin={4} bundle=[{5}] -> eligible={6} {8}")
    @CsvSource(delimiter = ',', nullValues = "-", textBlock = """
            # product, tier, employees, sites, gstin, bundle, eligible, conditional, expected reason codes
            P03, 2, 10, 1, ACTIVE,    -,       false, false, TIER_NOT_AVAILABLE
            P03, 1, 4,  1, ACTIVE,    -,       false, false, BELOW_MIN_EMPLOYEES
            P03, 1, 5,  1, ACTIVE,    -,       true,  false, -
            P03, 1, 5,  1, NONE,      -,       false, false, GSTIN_REQUIRED
            P03, 2, 3,  1, NONE,      -,       false, false, TIER_NOT_AVAILABLE;BELOW_MIN_EMPLOYEES;GSTIN_REQUIRED
            P10, 1, 20, 2, ACTIVE,    P02,     false, false, BELOW_MIN_SITES
            P10, 1, 9,  3, ACTIVE,    P02,     false, false, BELOW_MIN_EMPLOYEES
            P10, 1, 10, 3, ACTIVE,    P02,     true,  false, -
            P10, 1, 10, 3, ACTIVE,    -,       false, false, DEPENDENCY_MISSING
            P11, 1, 5,  1, NONE,      P04,     false, false, DEPENDENCY_MISSING
            P11, 1, 5,  1, NONE,      P02,     true,  false, -
            P11, 2, 5,  1, ACTIVE,    P03,     false, false, DEPENDENCY_REMOVED
            P22, 2, 3,  1, NONE,      P04,     true,  false, -
            P05, 1, 9,  1, ACTIVE,    -,       false, false, BELOW_MIN_EMPLOYEES
            P05, 1, 10, 1, ACTIVE,    -,       true,  false, -
            P06, 1, 24, 1, ACTIVE,    -,       false, false, BELOW_MIN_EMPLOYEES
            P06, 1, 25, 1, ACTIVE,    -,       true,  false, -
            P15, 1, 5,  1, CANCELLED, -,       false, false, GSTIN_INACTIVE
            P15, 1, 5,  1, SUSPENDED, -,       false, false, GSTIN_INACTIVE
            P15, 1, 5,  1, DECLARED,  -,       true,  true,  -
            P15, 1, 5,  1, INVALID,   -,       false, false, GSTIN_REQUIRED
            P15, 1, 5,  1, ACTIVE,    -,       true,  false, -
            P12, 1, 5,  1, NONE,      P02,     false, false, GSTIN_REQUIRED
            P12, 1, 5,  1, ACTIVE,    -,       false, false, DEPENDENCY_MISSING
            P01, 2, 1,  1, NONE,      -,       true,  false, -
            P04, 2, 1,  1, NONE,      -,       true,  false, -
            P09, 1, 5,  1, NONE,      P04,     false, false, DEPENDENCY_MISSING
            P09, 1, 5,  1, NONE,      P05,     false, false, DEPENDENCY_REMOVED
            P23, 1, 5,  1, NONE,      P04,     true,  false, -
            P21, 1, 10, 1, ACTIVE,    P05,     true,  false, -
            P21, 1, 9,  1, NONE,      P02,     false, false, BELOW_MIN_EMPLOYEES;GSTIN_REQUIRED
            P24, 1, 10, 1, DECLARED,  -,       true,  true,  -
            P12, 1, 3,  1, ACTIVE,    P01,     true,  false, -
            """)
    void catalogueRules(String product, int tier, int employees, int sites, String gstin, String bundle,
                        boolean eligible, boolean conditional, String codes) {
        ResolvedProfile profile = resolved(tier, employees, sites, gstin);
        EligibilityResult r = ELIGIBILITY.check(profile, product, ids(bundle));

        assertThat(r.eligible()).isEqualTo(eligible);
        assertThat(r.conditional()).isEqualTo(conditional);
        List<ReasonCode> expected = codes == null ? List.of()
                : Arrays.stream(codes.split(";")).map(ReasonCode::valueOf).toList();
        assertThat(r.reasons()).extracting(Reason::code).containsExactlyElementsOf(expected);
        if (conditional) {
            assertThat(r.condition()).isEqualTo(EligibilityService.KYC_CONDITION);
        }
    }

    @Test
    void messagesArePlainEnglish() {
        ResolvedProfile tier2 = resolved(2, 3, 2, "CANCELLED");
        assertThat(ELIGIBILITY.check(tier2, "P03", List.of()).reasons()).extracting(Reason::message)
                .contains("Biz Fibre 1G is not available in tier-2 cities");
        assertThat(ELIGIBILITY.check(tier2, "P10", List.of("P02")).reasons()).extracting(Reason::message)
                .contains("Managed SD-WAN needs at least 3 sites (you have 2)");
        assertThat(ELIGIBILITY.check(tier2, "P15", List.of()).reasons()).extracting(Reason::message)
                .containsExactly("WhatsApp Business API Starter needs an active GSTIN; your GSTIN is Cancelled");
    }

    @Test
    void fixedPointRemovesDependents() {
        BundleResolution r = ELIGIBILITY.resolveBundle(resolved(2, 10, 1, "ACTIVE"), List.of("P03", "P11"));
        assertThat(r.kept()).isEmpty();
        assertThat(r.result("P03").reasons()).extracting(Reason::code).containsExactly(ReasonCode.TIER_NOT_AVAILABLE);
        assertThat(r.result("P11").reasons()).extracting(Reason::code).containsExactly(ReasonCode.DEPENDENCY_REMOVED);
        assertThat(r.result("P11").reasons().get(0).message()).isEqualTo("Static IP removed because Biz Fibre 1G was removed");
    }

    @Test
    void fixedPointCascadesThroughAChain() {
        // P05 fails (employees), so P09 and P21 that could only rely on P05 are removed
        // (P04 AirFiber does not satisfy them); P22 accepts P04 and stays.
        BundleResolution r = ELIGIBILITY.resolveBundle(resolved(1, 5, 1, "ACTIVE"),
                List.of("P05", "P09", "P21", "P04", "P22"));
        assertThat(r.keptIds()).containsExactly("P04", "P22");
        assertThat(r.removed()).extracting(EligibilityResult::productId).containsExactly("P05", "P09", "P21");
        assertThat(r.result("P21").reasons()).extracting(Reason::code)
                .containsExactly(ReasonCode.BELOW_MIN_EMPLOYEES, ReasonCode.DEPENDENCY_REMOVED);
    }

    @Test
    void userRemovedDependencyIsReportedAsRemoved() {
        BundleResolution r = ELIGIBILITY.resolveBundle(resolved(1, 5, 1, "NONE"), List.of("P11", "P09"), List.of("P02"));
        assertThat(r.kept()).isEmpty();
        assertThat(r.removed()).allSatisfy(x -> assertThat(x.reasons()).extracting(Reason::code)
                .containsExactly(ReasonCode.DEPENDENCY_REMOVED));
    }

    @Test
    void checkAllReturnsAll25() {
        List<EligibilityResult> all = ELIGIBILITY.checkAll(resolved(1, 5, 1, "CANCELLED"), List.of("P02"));
        assertThat(all).hasSize(25);
        all.stream()
                .filter(r -> RulesFixture.DATA.catalogue().get(r.productId()).requiresGstin())
                .forEach(r -> assertThat(r.reasons()).extracting(Reason::code).contains(ReasonCode.GSTIN_INACTIVE));
    }
}
