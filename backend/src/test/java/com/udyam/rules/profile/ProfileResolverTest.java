package com.udyam.rules.profile;

import static com.udyam.rules.RulesFixture.RESOLVER;
import static com.udyam.rules.RulesFixture.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udyam.common.errors.ApiException;
import org.junit.jupiter.api.Test;

class ProfileResolverTest {

    private static BusinessProfile withCity(String businessType, String city, Integer tier, String gstin) {
        return new BusinessProfile(businessType, null, city, tier, 5, 1, gstin, null, null, null, null, null,
                null, null, null, null, null);
    }

    @Test
    void cityGivesTierAndState() {
        ResolvedProfile p = RESOLVER.resolve(withCity("Kirana store", "pune", null, null));
        assertThat(p.city()).isEqualTo("Pune");
        assertThat(p.cityTier()).isEqualTo(1);
        assertThat(p.cityState()).isEqualTo("Maharashtra");
        assertThat(p.segment()).isEqualTo(Segment.MICRO);
        assertThat(p.segmentSource()).isEqualTo("BUSINESS_TYPE");
    }

    @Test
    void unknownCityWithoutTierIsRejected() {
        assertThatThrownBy(() -> RESOLVER.resolve(withCity("Kirana store", "Atlantis", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cityTier");
    }

    @Test
    void unknownCityWithTierIsAccepted() {
        ResolvedProfile p = RESOLVER.resolve(withCity("Kirana store", "Atlantis", 2, null));
        assertThat(p.cityTier()).isEqualTo(2);
        assertThat(p.warnings()).isNotEmpty();
    }

    @Test
    void activeGstinIsVerifiedWithAutofillAndStateWarning() {
        ResolvedProfile p = RESOLVER.resolve(withCity("Dental clinic", "Mumbai", null, " 32ttnxb7384t1zw "));
        assertThat(p.gstinStatus()).isEqualTo(GstinStatus.VERIFIED_ACTIVE);
        assertThat(p.gstinAutofill().legalName()).isEqualTo("Balaji Dental Care LLP");
        assertThat(p.gstinAutofill().state()).isEqualTo("Kerala");
        assertThat(p.warnings()).anyMatch(w -> w.contains("Kerala") && w.contains("Maharashtra"));
    }

    @Test
    void cancelledAndSuspendedAreInactive() {
        ResolvedProfile cancelled = RESOLVER.resolve(profile(null, 1, 5, 1, "CANCELLED"));
        assertThat(cancelled.gstinStatus()).isEqualTo(GstinStatus.INACTIVE);
        assertThat(cancelled.gstinRegistryStatus()).isEqualTo("Cancelled");
        ResolvedProfile suspended = RESOLVER.resolve(profile(null, 1, 5, 1, "SUSPENDED"));
        assertThat(suspended.gstinRegistryStatus()).isEqualTo("Suspended");
    }

    @Test
    void invalidGstinReturnsErrorsAndNoCorrection() {
        ResolvedProfile bad = RESOLVER.resolve(profile(null, 1, 5, 1, "INVALID"));
        assertThat(bad.gstinStatus()).isEqualTo(GstinStatus.INVALID);
        assertThat(bad.gstinErrors()).isNotEmpty();

        // An OCR-style typo is NOT auto-corrected for typed input.
        ResolvedProfile typo = RESOLVER.resolve(withCity(null, null, 1, "19E0GXH4115Y1ZL"));
        assertThat(typo.gstinStatus()).isEqualTo(GstinStatus.INVALID);
        assertThat(typo.gstinAutofill()).isNull();
    }

    @Test
    void declaredGstinIsUnverified() {
        assertThat(RESOLVER.resolve(profile(null, 1, 5, 1, "DECLARED")).gstinStatus())
                .isEqualTo(GstinStatus.DECLARED_UNVERIFIED);
        assertThat(RESOLVER.resolve(profile(null, 1, 5, 1, "NONE")).gstinStatus()).isEqualTo(GstinStatus.NONE);
    }

    @Test
    void segmentFallbackForUnknownTypes() {
        assertThat(ProfileResolver.inferSegment("Cloud kitchen group", 20, 3)).isEqualTo(Segment.MULTI);
        assertThat(ProfileResolver.inferSegment("Steel fabrication factory", 30, 1)).isEqualTo(Segment.MFG);
        assertThat(ProfileResolver.inferSegment("Tea stall", 2, 1)).isEqualTo(Segment.MICRO);
        assertThat(ProfileResolver.inferSegment("Consultancy", 12, 1)).isEqualTo(Segment.PROF);
        assertThat(ProfileResolver.inferSegment(null, 6, 1)).isEqualTo(Segment.MICRO);
    }

    @Test
    void explicitSegmentWins() {
        BusinessProfile p = new BusinessProfile("Kirana store", "prof", null, 1, 5, 1, null, null, null, null, null,
                null, null, null, null, null, null);
        ResolvedProfile r = RESOLVER.resolve(p);
        assertThat(r.segment()).isEqualTo(Segment.PROF);
        assertThat(r.segmentSource()).isEqualTo("GIVEN");
    }
}
