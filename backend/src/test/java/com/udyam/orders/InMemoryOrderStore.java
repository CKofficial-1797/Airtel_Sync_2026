package com.udyam.orders;

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

/** OrderStore for unit tests (no DB). */
class InMemoryOrderStore implements OrderStore {

    final Map<UUID, Order> orders = new LinkedHashMap<>();
    final Map<UUID, List<Event>> events = new HashMap<>();
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
    public void addEvent(UUID orderId, String status, String note) {
        events.computeIfAbsent(orderId, k -> new ArrayList<>()).add(new Event(status, note, OffsetDateTime.now()));
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
