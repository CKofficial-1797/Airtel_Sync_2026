package com.udyam.orders;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** OrderStore for unit tests (no DB). Public: the KYC tests reuse it. */
public class InMemoryOrderStore implements OrderStore {

    public final Map<UUID, Order> orders = new LinkedHashMap<>();
    public final Map<UUID, List<Event>> events = new HashMap<>();
    private final Map<UUID, Tracking> tracking = new HashMap<>();
    final Set<UUID> sessions = new HashSet<>();
    final Map<UUID, UUID> checkedOut = new HashMap<>();
    private long seq = 41;

    @Override
    public long nextRefNumber() {
        return ++seq;
    }

    @Override
    public void insert(Order order) {
        boolean duplicateRef = orders.values().stream().anyMatch(o -> o.orderRef().equals(order.orderRef()));
        if (duplicateRef) {
            throw new IllegalStateException("order_ref must be unique");
        }
        orders.put(order.id(), order);
    }

    @Override
    public void addEvent(UUID orderId, String status, String note, String actor, LocalDate businessDate) {
        events.computeIfAbsent(orderId, k -> new ArrayList<>())
                .add(new Event(status, note, actor, businessDate, OffsetDateTime.now()));
    }

    @Override
    public Optional<Order> findByRef(String orderRef) {
        return orders.values().stream().filter(o -> o.orderRef().equals(orderRef)).findFirst();
    }

    @Override
    public Tracking tracking(UUID orderId) {
        return tracking.getOrDefault(orderId, new Tracking(null, null, null, null, null, 0));
    }

    @Override
    public void setInstallSlot(UUID orderId, LocalDate date, String slot) {
        Tracking t = tracking(orderId);
        tracking.put(orderId, new Tracking(date, slot, t.holdReason(), t.holdNote(), t.holdPreviousStatus(), t.viewCount()));
    }

    @Override
    public void setHold(UUID orderId, String reason, String note, String previousStatus) {
        Tracking t = tracking(orderId);
        tracking.put(orderId, new Tracking(t.slotDate(), t.slot(), reason, note, previousStatus, t.viewCount()));
    }

    @Override
    public void incrementTrackerViews(UUID orderId) {
        Tracking t = tracking(orderId);
        tracking.put(orderId, new Tracking(t.slotDate(), t.slot(), t.holdReason(), t.holdNote(), t.holdPreviousStatus(),
                t.viewCount() + 1));
    }

    @Override
    public void updateStatus(UUID orderId, String status) {
        Order o = orders.get(orderId);
        orders.put(orderId, new Order(o.id(), o.orderRef(), o.sessionId(), o.profile(), o.quote(),
                o.monthlyTotalInr(), o.oneTimeTotalInr(), o.contractMonths(), status, o.createdAt()));
    }

    @Override
    public Optional<Order> find(UUID orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public List<Event> events(UUID orderId) {
        return events.getOrDefault(orderId, List.of());
    }

    @Override
    public boolean sessionExists(UUID sessionId) {
        return sessions.contains(sessionId);
    }

    @Override
    public void markSessionCheckedOut(UUID sessionId, UUID orderId) {
        checkedOut.put(sessionId, orderId);
    }
}
