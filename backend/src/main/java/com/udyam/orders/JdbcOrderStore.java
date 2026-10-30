package com.udyam.orders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    public void addEvent(UUID orderId, String status, String note) {
        jdbc.update("INSERT INTO order_event (order_id, status, note) VALUES (?, ?, ?)", orderId, status, note);
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
        return jdbc.query("SELECT status, note, created_at FROM order_event WHERE order_id = ? ORDER BY id",
                (rs, i) -> new Event(rs.getString("status"), rs.getString("note"),
                        rs.getObject("created_at", OffsetDateTime.class)), orderId);
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
