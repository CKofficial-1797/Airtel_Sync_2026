package com.udyam.backoffice;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Who should follow up on a lead, and why. Deterministic and read top to bottom: the first matching rule wins.
 * Two rules matter most: a Micro lead never gets a field visit, and an inactive GSTIN gets fixed before anyone visits.
 */
public final class LeadRouter {

    // Channels
    public static final String DIGITAL_NUDGE = "DIGITAL_NUDGE";
    public static final String INSIDE_SALES = "INSIDE_SALES";
    public static final String FIELD_SALES = "FIELD_SALES";
    public static final String CHANNEL_PARTNER = "CHANNEL_PARTNER";
    public static final Set<String> CHANNELS = Set.of(DIGITAL_NUDGE, INSIDE_SALES, FIELD_SALES, CHANNEL_PARTNER);

    // Actions
    public static final String KYC_HELP = "KYC_HELP";
    public static final String TECH_FEASIBILITY = "TECH_FEASIBILITY";
    public static final String RESOLVE_HOLD = "RESOLVE_HOLD";
    public static final String FINISH_ORDER = "FINISH_ORDER";
    public static final String FIX_GST_FIRST = "FIX_GST_FIRST";
    public static final String FOLLOW_UP_QUOTE = "FOLLOW_UP_QUOTE";
    public static final String CALL = "CALL";
    public static final String VISIT = "VISIT";
    public static final String NUDGE = "NUDGE";

    public static final String MICRO_SEGMENT = "Micro retail & services";

    private static final String MICRO_WHY = "Field visits cost about ₹53k per Micro activation, against a bundle of about ₹2.5k/month.";
    private static final Set<String> QUOTE_STAGES = Set.of("RECOMMENDED", "CUSTOMISING", "READY_FOR_CHECKOUT");

    private LeadRouter() {
    }

    /**
     * @param sessionStage     advisor stage, SESSION only
     * @param orderStatus      order status, ORDER only
     * @param holdReason       ORDER on hold only
     * @param failedCheck      first failed KYC check of the latest run, ORDER only
     * @param quoteMonthlyInr  the quote they saw, if any
     * @param partnerInCity    a channel partner is active in the city
     * @param airFiberEligible 5G AirFiber eligibility from the rules engine; null if unknown
     */
    public record Context(String segment, String band, String gstinStatus, String city, String sessionStage,
                          String orderStatus, String holdReason, String failedCheck, Integer quoteMonthlyInr,
                          boolean partnerInCity, Boolean airFiberEligible) {
    }

    public record Route(String action, String channel, String why) {
    }

    public static Route route(Context c) {
        if (c.orderStatus() != null) {
            return routeOrder(c);
        }
        if (LeadProfile.GST_INACTIVE.equals(c.gstinStatus())) {
            return new Route(FIX_GST_FIRST, INSIDE_SALES,
                    "The GSTIN isn't active, so KYC would reject this order. Help them reactivate it before any visit.");
        }
        if (c.sessionStage() != null && QUOTE_STAGES.contains(c.sessionStage()) && c.quoteMonthlyInr() != null) {
            return new Route(FOLLOW_UP_QUOTE, INSIDE_SALES, "Saw a quote of " + inr(c.quoteMonthlyInr()) + "/month and didn't proceed.");
        }
        if (MICRO_SEGMENT.equals(c.segment())) {
            return PropensityScorer.HOT.equals(c.band())
                    ? new Route(CALL, INSIDE_SALES, "A strong Micro lead is worth a call, not a visit. " + MICRO_WHY)
                    : new Route(NUDGE, DIGITAL_NUDGE, "Send a reminder with a link back to the quote. " + MICRO_WHY);
        }
        if (PropensityScorer.HOT.equals(c.band())) {
            if (c.segment() == null) {
                return new Route(CALL, INSIDE_SALES, "A strong lead, but the business type is unknown: call before planning a visit.");
            }
            return c.partnerInCity()
                    ? new Route(VISIT, CHANNEL_PARTNER, "A strong lead, and a channel partner is active in " + c.city() + ".")
                    : new Route(VISIT, FIELD_SALES, "A strong lead, and no active channel partner in " + c.city() + ".");
        }
        if (PropensityScorer.WARM.equals(c.band())) {
            return new Route(CALL, INSIDE_SALES, "A promising lead: a call is enough to find out more.");
        }
        return new Route(NUDGE, DIGITAL_NUDGE, "Low score: a reminder costs little, and a call isn't worth it yet.");
    }

    private static Route routeOrder(Context c) {
        if ("KYC_ACTION_REQUIRED".equals(c.orderStatus())) {
            return new Route(KYC_HELP, INSIDE_SALES, "Help them fix this: " + (c.failedCheck() == null ? "a failed KYC check." : c.failedCheck()));
        }
        if ("ON_HOLD".equals(c.orderStatus())) {
            if ("SITE_NOT_FEASIBLE".equals(c.holdReason())) {
                String offer = c.airFiberEligible() == null ? "Check whether 5G AirFiber fits."
                        : c.airFiberEligible() ? "Offer 5G AirFiber: this business is eligible."
                        : "5G AirFiber isn't available for this business either.";
                return new Route(TECH_FEASIBILITY, INSIDE_SALES, "Fibre isn't available at this address. " + offer);
            }
            return new Route(RESOLVE_HOLD, INSIDE_SALES, "On hold: " + (c.holdReason() == null ? "reason not recorded" : c.holdReason()) + ".");
        }
        String where = "KYC_IN_PROGRESS".equals(c.orderStatus()) ? "They consented but haven't finished uploading documents."
                : "They accepted the quote but haven't given KYC consent.";
        return new Route(FINISH_ORDER, INSIDE_SALES, where);
    }

    private static String inr(int amount) {
        return "₹" + NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN")).format(amount);
    }
}
