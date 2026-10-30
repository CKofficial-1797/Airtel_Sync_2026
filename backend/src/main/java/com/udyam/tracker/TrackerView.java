package com.udyam.tracker;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /api/orders/{id}/tracker. No identifiers (GSTIN, Udyam, ID numbers) appear here at all.
 *
 * @param customerStep           the step in progress (1-5); 5 when active
 * @param items                  per-product ETAs, fastest first; etaDate null until the documents are verified
 * @param expectedActivationDate later of the slowest item and the booked slot; null before verification, on hold,
 *                               rejected or cancelled (no date we could honestly promise)
 * @param blocked                why step 2 is blocked (ACTION_REQUIRED, REJECTED) or that the order was cancelled
 */
public record TrackerView(
        String orderId,
        String orderRef,
        String businessName,
        String currentStatus,
        int customerStep,
        List<StepMapper.Step> steps,
        List<EtaCalculator.ItemEta> items,
        int etaDaysAfterVerification,
        LocalDate expectedActivationDate,
        String etaMessageEn,
        String etaMessageHi,
        InstallSlot installSlot,
        NextAction nextAction,
        OnHold onHold,
        Blocked blocked,
        List<EventLine> events,
        OffsetDateTime lastUpdated,
        int monthlyTotalInr) {

    public record InstallSlot(LocalDate date, String slot) {
    }

    /** type: GIVE_CONSENT | UPLOAD_DOCS | FIX_DOCS | PICK_SLOT | NONE; link: where to go, null when handled here. */
    public record NextAction(String type, String labelEn, String labelHi, String link) {
    }

    public record OnHold(String reasonCode, String messageEn, String messageHi, String nextEn, String nextHi) {
    }

    public record Blocked(String messageEn, String messageHi, String helpEn, String helpHi) {
    }

    /** Customer-friendly activity line; `at` is the real time, businessDate the date it counts for. */
    public record EventLine(String status, String textEn, String textHi, OffsetDateTime at, LocalDate businessDate) {
    }
}
