package com.udyam.backoffice;

import static com.udyam.backoffice.LeadRouter.MICRO_SEGMENT;
import static com.udyam.backoffice.PropensityScorer.COLD;
import static com.udyam.backoffice.PropensityScorer.HOT;
import static com.udyam.backoffice.PropensityScorer.WARM;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LeadRouterTest {

    private static final String PROF = "Professional offices";

    private static LeadRouter.Route lead(String segment, String band, String gst, boolean partner) {
        return LeadRouter.route(new LeadRouter.Context(segment, band, gst, "Nagpur", null, null, null, null, null, partner, null));
    }

    private static LeadRouter.Route order(String status, String holdReason, String failed, Boolean airFiber) {
        return LeadRouter.route(new LeadRouter.Context(PROF, HOT, LeadProfile.GST_ACTIVE, "Nagpur", null, status, holdReason, failed, 2500, true, airFiber));
    }

    private static LeadRouter.Route session(String segment, String stage, Integer quote) {
        return LeadRouter.route(new LeadRouter.Context(segment, COLD, LeadProfile.GST_NONE, "Nagpur", stage, null, null, null, quote, false, null));
    }

    @Test
    void microNeverGetsAFieldVisit() {
        for (String band : List.of(HOT, WARM, COLD)) {
            for (String gst : List.of(LeadProfile.GST_NONE, LeadProfile.GST_ACTIVE, LeadProfile.GST_INACTIVE)) {
                for (boolean partner : List.of(true, false)) {
                    assertThat(lead(MICRO_SEGMENT, band, gst, partner).channel()).isIn(LeadRouter.INSIDE_SALES, LeadRouter.DIGITAL_NUDGE);
                }
            }
        }
    }

    @Test
    void microGetsACallWhenHotAndANudgeOtherwise() {
        LeadRouter.Route hot = lead(MICRO_SEGMENT, HOT, LeadProfile.GST_NONE, true);
        assertThat(hot.channel()).isEqualTo(LeadRouter.INSIDE_SALES);
        assertThat(hot.why()).contains("₹53k");
        assertThat(lead(MICRO_SEGMENT, WARM, LeadProfile.GST_NONE, true).channel()).isEqualTo(LeadRouter.DIGITAL_NUDGE);
        assertThat(lead(MICRO_SEGMENT, COLD, LeadProfile.GST_NONE, true).channel()).isEqualTo(LeadRouter.DIGITAL_NUDGE);
    }

    @Test
    void aHotLeadGoesToAPartnerIfTheCityHasOneElseField() {
        assertThat(lead(PROF, HOT, LeadProfile.GST_ACTIVE, true).channel()).isEqualTo(LeadRouter.CHANNEL_PARTNER);
        LeadRouter.Route field = lead(PROF, HOT, LeadProfile.GST_ACTIVE, false);
        assertThat(field.channel()).isEqualTo(LeadRouter.FIELD_SALES);
        assertThat(field.why()).contains("Nagpur");
    }

    @Test
    void warmGetsACallAndColdGetsANudge() {
        assertThat(lead(PROF, WARM, LeadProfile.GST_ACTIVE, true).channel()).isEqualTo(LeadRouter.INSIDE_SALES);
        assertThat(lead(PROF, COLD, LeadProfile.GST_ACTIVE, true).channel()).isEqualTo(LeadRouter.DIGITAL_NUDGE);
    }

    @Test
    void anUnknownSegmentNeverGetsAVisit() {
        assertThat(lead(null, HOT, LeadProfile.GST_NONE, false).channel()).isEqualTo(LeadRouter.INSIDE_SALES);
        assertThat(lead(null, HOT, LeadProfile.GST_NONE, true).channel()).isEqualTo(LeadRouter.INSIDE_SALES);
    }

    @Test
    void anInactiveGstinIsFixedBeforeAnyVisit() {
        LeadRouter.Route r = lead(PROF, HOT, LeadProfile.GST_INACTIVE, true);
        assertThat(r.action()).isEqualTo(LeadRouter.FIX_GST_FIRST);
        assertThat(r.channel()).isEqualTo(LeadRouter.INSIDE_SALES);
    }

    @Test
    void aSessionThatSawAQuoteGetsACallEvenIfMicroOrCold() {
        LeadRouter.Route r = session(MICRO_SEGMENT, "READY_FOR_CHECKOUT", 2499);
        assertThat(r.action()).isEqualTo(LeadRouter.FOLLOW_UP_QUOTE);
        assertThat(r.channel()).isEqualTo(LeadRouter.INSIDE_SALES);
        assertThat(r.why()).contains("₹2,499");
    }

    @Test
    void aSessionStillCollectingIsRoutedOnItsScore() {
        assertThat(session(MICRO_SEGMENT, "COLLECTING", null).channel()).isEqualTo(LeadRouter.DIGITAL_NUDGE);
        assertThat(session(PROF, "COLLECTING", null).channel()).isEqualTo(LeadRouter.DIGITAL_NUDGE);
    }

    @Test
    void kycActionRequiredGetsHelpWithTheFailedCheck() {
        LeadRouter.Route r = order("KYC_ACTION_REQUIRED", null, "The address proof is 130 days old; it must be at most 90 days old.", null);
        assertThat(r.action()).isEqualTo(LeadRouter.KYC_HELP);
        assertThat(r.channel()).isEqualTo(LeadRouter.INSIDE_SALES);
        assertThat(r.why()).contains("90 days");
    }

    @Test
    void holdsAreResolvedByInsideSales() {
        assertThat(order("ON_HOLD", "SITE_NOT_FEASIBLE", null, true).action()).isEqualTo(LeadRouter.TECH_FEASIBILITY);
        assertThat(order("ON_HOLD", "SITE_NOT_FEASIBLE", null, true).why()).contains("eligible");
        assertThat(order("ON_HOLD", "SITE_NOT_FEASIBLE", null, false).why()).contains("isn't available");
        assertThat(order("ON_HOLD", "SITE_NOT_FEASIBLE", null, null).why()).contains("Check");
        assertThat(order("ON_HOLD", "CUSTOMER_UNREACHABLE", null, null).action()).isEqualTo(LeadRouter.RESOLVE_HOLD);
    }

    @Test
    void anUnfinishedOrderIsNudgedToFinish() {
        assertThat(order("QUOTE_ACCEPTED", null, null, null).action()).isEqualTo(LeadRouter.FINISH_ORDER);
        assertThat(order("KYC_PENDING_CONSENT", null, null, null).why()).contains("consent");
        assertThat(order("KYC_IN_PROGRESS", null, null, null).why()).contains("documents");
    }
}
