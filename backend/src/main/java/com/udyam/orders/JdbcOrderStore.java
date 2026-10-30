package com.udyam.orders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** OrderStore on Postgres. */
@Repository
public class JdbcOrderStore implements OrderStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcOrderStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public long nextRefNumber() {
        Long n = jdbc.queryForObject("SELECT nextval('order_ref_seq')", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public void insert(Order o) {
        jdbc.update("""
                INSERT INTO customer_order (id, order_ref, session_id, profile_json, quote_json, monthly_total_inr,
                    one_time_total_inr, contract_months, status)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                """, o.id(), o.orderRef(), o.sessionId(), o.profile().toString(), o.quote().toString(),
                o.monthlyTotalInr(), o.oneTimeTotalInr(), o.contractMonths(), o.status());
    }

    @Override
    public void addEvent(UUID orderId, String status, String note, String actor, LocalDate businessDate) {
        jdbc.update("INSERT INTO order_event (order_id, status, note, actor, business_date) VALUES (?, ?, ?, ?, ?)",
                orderId, status, note, actor, businessDate);
    }

    @Override
    public Optional<Order> findByRef(String orderRef) {
        return jdbc.query("SELECT id FROM customer_order WHERE order_ref = ?",
                (rs, i) -> rs.getObject("id", UUID.class), orderRef).stream().findFirst().flatMap(this::find);
    }

    @Override
    public Tracking tracking(UUID orderId) {
        return jdbc.query("""
                SELECT install_slot_date, install_slot, hold_reason, hold_note, hold_previous_status, tracker_view_count
                FROM customer_order WHERE id = ?
                """, (rs, i) -> new Tracking(rs.getObject("install_slot_date", LocalDate.class),
                rs.getString("install_slot"), rs.getString("hold_reason"), rs.getString("hold_note"),
                rs.getString("hold_previous_status"), rs.getInt("tracker_view_count")), orderId)
                .stream().findFirst().orElse(new Tracking(null, null, null, null, null, 0));
    }

    @Override
    public void setInstallSlot(UUID orderId, LocalDate date, String slot) {
        jdbc.update("UPDATE customer_order SET install_slot_date = ?, install_slot = ?, updated_at = now() WHERE id = ?",
                date, slot, orderId);
    }

    @Override
    public void setHold(UUID orderId, String reason, String note, String previousStatus) {
        jdbc.update("""
                UPDATE customer_order SET hold_reason = ?, hold_note = ?, hold_previous_status = ?, updated_at = now()
                WHERE id = ?
                """, reason, note, previousStatus, orderId);
    }

    @Override
    public void incrementTrackerViews(UUID orderId) {
        jdbc.update("UPDATE customer_order SET tracker_view_count = tracker_view_count + 1 WHERE id = ?", orderId);
    }

    @Override
    public void updateStatus(UUID orderId, String status) {
        jdbc.update("UPDATE customer_order SET status = ?, updated_at = now() WHERE id = ?", status, orderId);
    }

    @Override
    public Optional<Order> find(UUID orderId) {
        return jdbc.query("""
                SELECT id, order_ref, session_id, profile_json::text AS profile, quote_json::text AS quote,
                       monthly_total_inr, one_time_total_inr, contract_months, status, created_at
                FROM customer_order WHERE id = ?
                """, (rs, i) -> new Order(rs.getObject("id", UUID.class), rs.getString("order_ref"),
                rs.getObject("session_id", UUID.class), read(rs.getString("profile")), read(rs.getString("quote")),
                rs.getInt("monthly_total_inr"), rs.getInt("one_time_total_inr"), rs.getInt("contract_months"),
                rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class)), orderId)
                .stream().findFirst();
    }

    @Override
    public List<Event> events(UUID orderId) {
        return jdbc.query("""
                SELECT status, note, actor, business_date, created_at FROM order_event WHERE order_id = ? ORDER BY id
                """, (rs, i) -> new Event(rs.getString("status"), rs.getString("note"), rs.getString("actor"),
                rs.getObject("business_date", LocalDate.class), rs.getObject("created_at", OffsetDateTime.class)),
                orderId);
    }

    @Override
    public boolean sessionExists(UUID sessionId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM advisor_session WHERE id = ?", Integer.class, sessionId);
        return n != null && n > 0;
    }

    @Override
    public void markSessionCheckedOut(UUID sessionId, UUID orderId) {
        jdbc.update("UPDATE advisor_session SET stage = 'CHECKOUT_DONE', order_id = ?, updated_at = now() WHERE id = ?",
                orderId, sessionId);
    }

    private JsonNode read(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON in customer_order", e);
        }
    }
}
