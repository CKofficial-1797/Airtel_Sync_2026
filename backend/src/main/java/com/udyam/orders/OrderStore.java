package com.udyam.orders;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for orders. App: JdbcOrderStore (Neon); tests: an in-memory version. */
public interface OrderStore {

    /** Next value of the order reference sequence (1, 2, 3, ...). */
    long nextRefNumber();

    void insert(Order order);

    /** actor: EventActor.*; businessDate: the date the event counts for (created_at stays the real time). */
    void addEvent(UUID orderId, String status, String note, String actor, LocalDate businessDate);

    void updateStatus(UUID orderId, String status);

    Optional<Order> find(UUID orderId);

    Optional<Order> findByRef(String orderRef);

    Tracking tracking(UUID orderId);

    /** Null date and slot clear it. */
    void setInstallSlot(UUID orderId, LocalDate date, String slot);

    /** All null clears it. */
    void setHold(UUID orderId, String reason, String note, String previousStatus);

    void incrementTrackerViews(UUID orderId);

    List<Event> events(UUID orderId);

    boolean sessionExists(UUID sessionId);

    /** Marks the advisor session as closed (stage CHECKOUT_DONE) and links it to the order. */
    void markSessionCheckedOut(UUID sessionId, UUID orderId);

    record Order(UUID id, String orderRef, UUID sessionId, JsonNode profile, JsonNode quote, int monthlyTotalInr,
                 int oneTimeTotalInr, int contractMonths, String status, OffsetDateTime createdAt) {
    }

    record Event(String status, String note, String actor, LocalDate businessDate, OffsetDateTime createdAt) {
    }

    /** The tracker's columns on customer_order. */
    record Tracking(LocalDate slotDate, String slot, String holdReason, String holdNote, String holdPreviousStatus,
                    int viewCount) {
    }
}
