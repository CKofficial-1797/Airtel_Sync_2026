package com.udyam.orders;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for orders. App: JdbcOrderStore (Neon); tests: an in-memory version. */
public interface OrderStore {

    /** Next value of the order reference sequence (1, 2, 3, ...). */
    long nextRefNumber();

    void insert(Order order);

    void addEvent(UUID orderId, String status, String note);

    Optional<Order> find(UUID orderId);

    List<Event> events(UUID orderId);

    boolean sessionExists(UUID sessionId);

    /** Marks the advisor session as closed (stage CHECKOUT_DONE) and links it to the order. */
    void markSessionCheckedOut(UUID sessionId, UUID orderId);

    record Order(UUID id, String orderRef, UUID sessionId, JsonNode profile, JsonNode quote, int monthlyTotalInr,
                 int oneTimeTotalInr, int contractMonths, String status, OffsetDateTime createdAt) {
    }

    record Event(String status, String note, OffsetDateTime createdAt) {
    }
}
