package com.udyam.rules.recommend;

import static com.udyam.rules.RulesFixture.ELIGIBILITY;
import static com.udyam.rules.RulesFixture.RECOMMENDER;
import static com.udyam.rules.RulesFixture.profile;
import static org.assertj.core.api.Assertions.assertThat;

import com.udyam.rules.RulesFixture;
import com.udyam.rules.eligibility.EligibilityResult;
import com.udyam.rules.eligibility.Reason;
import com.udyam.rules.eligibility.ReasonCode;
import com.udyam.rules.profile.BusinessProfile;
import com.udyam.rules.profile.Need;
import com.udyam.rules.profile.Segment;
import com.udyam.rules.recommend.Recommendation.Line;
import com.udyam.rules.recommend.Recommendation.Role;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RecommendationServiceTest {

    private static Map<String, Integer> included(Recommendation r) {
        return r.included().stream().collect(Collectors.toMap(Line::productId, Line::quantity));
    }

    private static List<String> offered(Recommendation r) {
        return r.optionalAddOns().stream().map(Line::productId).toList();
    }

    private static EligibilityResult excluded(Recommendation r, String id) {
        return r.excluded().stream().filter(e -> e.productId().equals(id)).findFirst().orElseThrow();
    }

    /** Invariant for every scenario: everything included is eligible in the final bundle. */
    private static void assertAllIncludedEligible(Recommendation r) {
        List<String> bundle = r.included().stream().map(Line::productId).toList();
        for (String id : bundle) {
            assertThat(ELIGIBILITY.check(r.resolvedProfile(), id, bundle).eligible()).as(id).isTrue();
        }
        assertThat(r.quote().removed()).isEmpty();
    }

    @Test
    void kiranaTier2NoGstin() {
        Recommendation r = RECOMMENDER.recommend(profile("Kirana store", 2, 3, 1, "NONE"));
        assertThat(r.segment()).isEqualTo(Segment.MICRO);
        assertThat(included(r)).containsExactlyInAnyOrderEntriesOf(Map.of("P01", 1, "P07", 2));
        assertThat(r.included().get(0).role()).isEqualTo(Role.PRIMARY);
        assertThat(excluded(r, "P15").reasons()).extracting(Reason::code).containsExactly(ReasonCode.GSTIN_REQUIRED);
        assertThat(excluded(r, "P14").reasons()).extracting(Reason::code).containsExactly(ReasonCode.GSTIN_REQUIRED);
        assertThat(offered(r)).contains("P19", "P22", "P23", "P18").doesNotContain("P15", "P14");
        assertAllIncludedEligible(r);
    }

    @Test
    void kiranaWantingWhatsAppAndCctv() {
        Recommendation r = RECOMMENDER.recommend(profile("Kirana store", 2, 3, 1, "ACTIVE", false,
                Set.of(Need.WHATSAPP, Need.CCTV)));
        assertThat(included(r)).containsKeys("P01", "P07", "P15", "P23");
        assertThat(r.included()).filteredOn(l -> l.productId().equals("P15")).extracting(Line::role)
                .containsExactly(Role.NEED);
        assertThat(included(r).get("P23")).isEqualTo(2);
        assertAllIncludedEligible(r);
    }

    @Test
    void salonWithoutFibreGetsAirFiber() {
        Recommendation r = RECOMMENDER.recommend(profile("Salon", 2, 4, 1, "NONE", true, Set.of(Need.STATIC_IP)));
        assertThat(included(r)).containsKeys("P04").doesNotContainKeys("P01", "P11");
        assertThat(offered(r)).contains("P22").doesNotContain("P11");
        assertThat(excluded(r, "P11").reasons()).extracting(Reason::code).containsExactly(ReasonCode.DEPENDENCY_MISSING);
        assertAllIncludedEligible(r);
    }

    @Test
    void dentalClinicTier1WithGstin() {
        Recommendation r = RECOMMENDER.recommend(profile("Dental clinic", 1, 14, 1, "ACTIVE"));
        assertThat(r.segment()).isEqualTo(Segment.PROF);
        assertThat(included(r)).containsExactlyInAnyOrderEntriesOf(
                Map.of("P02", 1, "P16", 10, "P08", 3, "P20", 7, "P17", 1));
        Line upgrade = r.optionalAddOns().stream().filter(l -> l.productId().equals("P03")).findFirst().orElseThrow();
        assertThat(upgrade.role()).isEqualTo(Role.UPGRADE);
        assertThat(upgrade.replaces()).isEqualTo("P02");
        assertThat(r.included().get(0).why()).isEqualTo("300 Mbps fibre for 14 staff: smooth video calls and cloud apps");
        assertAllIncludedEligible(r);
    }

    @Test
    void caFirmTier2NeverGetsGigabitFibre() {
        Recommendation r = RECOMMENDER.recommend(profile("CA firm", 2, 12, 1, "ACTIVE"));
        assertThat(included(r)).doesNotContainKey("P03");
        assertThat(offered(r)).doesNotContain("P03");
        assertThat(excluded(r, "P03").reasons()).extracting(Reason::code).contains(ReasonCode.TIER_NOT_AVAILABLE);
        assertAllIncludedEligible(r);
    }

    @Test
    void restaurantChainEightSites() {
        Recommendation r = RECOMMENDER.recommend(profile("Restaurant chain", 1, 60, 8, "ACTIVE"));
        assertThat(r.segment()).isEqualTo(Segment.MULTI);
        assertThat(included(r)).containsExactlyInAnyOrderEntriesOf(
                Map.of("P02", 8, "P09", 8, "P19", 8, "P23", 16, "P10", 8, "P08", 5));
        assertAllIncludedEligible(r);
    }

    @Test
    void retailChainTooSmallForSdWan() {
        Recommendation r = RECOMMENDER.recommend(profile("Apparel retail chain", 1, 9, 3, "ACTIVE"));
        assertThat(included(r)).doesNotContainKey("P10");
        assertThat(excluded(r, "P10").reasons()).extracting(Reason::code)
                .containsExactly(ReasonCode.BELOW_MIN_EMPLOYEES);
        assertAllIncludedEligible(r);
    }

    @Test
    void textileUnitGetsLeasedLine() {
        Recommendation r = RECOMMENDER.recommend(profile("Textile unit", 1, 85, 1, "ACTIVE"));
        assertThat(r.segment()).isEqualTo(Segment.MFG);
        assertThat(r.included().get(0).productId()).isEqualTo("P05");
        assertThat(included(r)).containsEntry("P16", 13).containsEntry("P08", 4).containsEntry("P20", 9);
        assertThat(r.optionalAddOns()).filteredOn(l -> l.productId().equals("P06")).extracting(Line::replaces)
                .containsExactly("P05");
        assertAllIncludedEligible(r);
    }

    @Test
    void smallFactoryFallsBackToFibre300() {
        Recommendation r = RECOMMENDER.recommend(profile("Furniture workshop", 2, 8, 1, "ACTIVE"));
        assertThat(r.included().get(0).productId()).isEqualTo("P02");
        assertThat(excluded(r, "P05").reasons()).extracting(Reason::code).containsExactly(ReasonCode.BELOW_MIN_EMPLOYEES);
        assertThat(excluded(r, "P03").reasons()).extracting(Reason::code).containsExactly(ReasonCode.TIER_NOT_AVAILABLE);
        assertThat(r.notes()).anyMatch(n -> n.startsWith("Internet Leased Line 20 not suggested"))
                .anyMatch(n -> n.startsWith("Biz Fibre 1G not suggested"))
                .anyMatch(n -> n.equals("Recommended Biz Fibre 300 for connectivity instead"));
        assertAllIncludedEligible(r);
    }

    @Test
    void manufacturerWithCancelledGstinGetsNoGstProducts() {
        Recommendation r = RECOMMENDER.recommend(profile("Textile unit", 1, 85, 1, "CANCELLED",
                false, Set.of(Need.MACHINE_IOT, Need.SECURITY)));
        for (Line l : r.included()) {
            assertThat(RulesFixture.DATA.catalogue().get(l.productId()).requiresGstin()).as(l.productId()).isFalse();
        }
        for (Line l : r.optionalAddOns()) {
            assertThat(RulesFixture.DATA.catalogue().get(l.productId()).requiresGstin()).as(l.productId()).isFalse();
        }
        assertThat(r.included().get(0).productId()).isEqualTo("P02");
        assertThat(excluded(r, "P24").reasons()).extracting(Reason::code).containsExactly(ReasonCode.GSTIN_INACTIVE);
        assertAllIncludedEligible(r);
    }

    @Test
    void declaredGstinIncludesConditionally() {
        Recommendation r = RECOMMENDER.recommend(profile("Kirana store", 2, 3, 1, "DECLARED", false,
                Set.of(Need.WHATSAPP)));
        Line whatsapp = r.included().stream().filter(l -> l.productId().equals("P15")).findFirst().orElseThrow();
        assertThat(whatsapp.conditional()).isTrue();
        assertThat(r.quote().conditional()).isTrue();
    }

    @Test
    void explicitProfileQuantitiesWin() {
        BusinessProfile p = new BusinessProfile("Dental clinic", null, null, 1, 14, 1, null, null, null, null, null,
                Set.of(Need.CCTV), 4, 6, 3, null, null);
        Map<String, Integer> q = included(RECOMMENDER.recommend(p));
        assertThat(q).containsEntry("P16", 4).containsEntry("P20", 4).containsEntry("P08", 6).containsEntry("P23", 3);
    }
}
