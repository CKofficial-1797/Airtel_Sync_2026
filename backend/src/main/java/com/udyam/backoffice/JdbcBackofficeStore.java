package com.udyam.backoffice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udyam.advisor.AdvisorStore;
import com.udyam.common.privacy.Masking;
import com.udyam.rules.audit.MisSellingAuditService;
import com.udyam.rules.audit.MisSellingReport;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** BackofficeStore on Postgres. */
@Repository
public class JdbcBackofficeStore implements BackofficeStore {

    private static final String ORDER_SELECT = """
            SELECT o.id, o.order_ref, o.status, o.profile_json::text AS profile, o.quote_json::text AS quote,
                   o.monthly_total_inr, o.hold_reason, e.last_activity,
                   (SELECT jsonb_object_agg(d.doc_type, d.extracted_json)::text FROM kyc_document d
                     WHERE d.order_id = o.id AND d.extracted_json IS NOT NULL) AS kyc_fields,
                   f.message_en, f.message_hi
            FROM customer_order o
            JOIN LATERAL (SELECT GREATEST(o.updated_at, max(created_at)) AS last_activity FROM order_event WHERE order_id = o.id) e ON true
            LEFT JOIN LATERAL (
                SELECT c.message_en, c.message_hi FROM kyc_check c
                WHERE c.order_id = o.id AND c.status = 'FAIL'
                  AND c.run_id = (SELECT run_id FROM kyc_check WHERE order_id = o.id ORDER BY id DESC LIMIT 1)
                ORDER BY c.check_code LIMIT 1) f ON true
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MisSellingAuditService audit;

    /** Provided data never changes, and 5,000 rows from Neon are slow: read them once. */
    private volatile List<HistoricalLead> historical;

    public JdbcBackofficeStore(JdbcTemplate jdbc, ObjectMapper json, MisSellingAuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    @Override
    public List<HistoricalLead> historicalLeads() {
        List<HistoricalLead> leads = historical;
        if (leads == null) {
            leads = List.copyOf(readHistoricalLeads());
            historical = leads;
        }
        return leads;
    }

    private List<HistoricalLead> readHistoricalLeads() {
        return jdbc.query("""
                SELECT l.*, g.status AS registry_status,
                       GREATEST(l.created_date, l.contacted_date, l.needs_assessed_date, l.quote_accepted_date,
                                l.kyc_complete_date, l.activated_date) AS last_activity
                FROM smb_lead l LEFT JOIN gstin_registry g ON g.gstin = l.gstin
                ORDER BY l.lead_id
                """, (rs, i) -> {
            Array products = rs.getArray("products_of_interest");
            String gstin = rs.getString("gstin");
            return new HistoricalLead(rs.getString("lead_id"), rs.getObject("created_date", LocalDate.class),
                    rs.getObject("last_activity", LocalDate.class), rs.getString("lead_source"), rs.getString("business_name"),
                    rs.getString("business_type"), rs.getString("segment"), rs.getString("city"), rs.getInt("city_tier"),
                    rs.getInt("employee_count"), rs.getInt("num_sites"), rs.getBoolean("has_gstin"),
                    gstin == null ? null : Masking.gstin(gstin.trim()), rs.getString("registry_status"), rs.getBoolean("has_udyam"),
                    rs.getInt("monthly_telecom_spend_inr"), rs.getString("current_internet"),
                    products == null ? List.of() : List.of((String[]) products.getArray()), rs.getString("stage_reached"),
                    rs.getString("outcome"), rs.getString("drop_reason"), rs.getInt("acquisition_cost_inr"),
                    rs.getObject("monthly_bundle_value_inr", Integer.class), rs.getObject("churned_within_6_months", Boolean.class));
        });
    }

    @Override
    public List<AdvisorStore.Session> idleSessions(OffsetDateTime lastActiveBefore) {
        return jdbc.query("""
                SELECT id, created_at, updated_at, ui_language, detected_language, stage, profile_json::text AS profile,
                       last_recommendation_json::text AS recommendation, last_quote_json::text AS quote
                FROM advisor_session
                WHERE order_id IS NULL AND stage <> 'CHECKOUT_DONE' AND updated_at <= ?
                """, (rs, i) -> new AdvisorStore.Session(rs.getObject("id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                rs.getString("ui_language"), rs.getString("detected_language"), rs.getString("stage"),
                read(rs.getString("profile")), read(rs.getString("recommendation")), read(rs.getString("quote"))), lastActiveBefore);
    }

    @Override
    public List<OrderLead> stuckOrders(Collection<String> statuses, OffsetDateTime lastActiveBefore) {
        List<Object> args = new ArrayList<>(statuses);
        args.add(lastActiveBefore);
        String marks = String.join(", ", statuses.stream().map(s -> "?").toList());
        return jdbc.query(ORDER_SELECT + "WHERE o.status IN (" + marks + ") AND jsonb_typeof(o.profile_json) = 'object' AND e.last_activity <= ?",
                orderMapper(), args.toArray());
    }

    @Override
    public Optional<OrderLead> orderLead(UUID orderId) {
        return jdbc.query(ORDER_SELECT + "WHERE o.id = ?", orderMapper(), orderId).stream().findFirst();
    }

    private RowMapper<OrderLead> orderMapper() {
        return (ResultSet rs, int i) -> new OrderLead(rs.getObject("id", UUID.class), rs.getString("order_ref"), rs.getString("status"),
                read(rs.getString("profile")), read(rs.getString("quote")), rs.getInt("monthly_total_inr"), rs.getString("hold_reason"),
                rs.getObject("last_activity", OffsetDateTime.class), fields(rs.getString("kyc_fields")), rs.getString("message_en"),
                rs.getString("message_hi"));
    }

    private Map<String, JsonNode> fields(String text) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        JsonNode node = read(text);
        if (node != null) {
            node.properties().forEach(e -> map.put(e.getKey(), e.getValue()));
        }
        return map;
    }

    @Override
    public Map<String, Assignment> assignments() {
        Map<String, Assignment> map = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM lead_assignment", (ResultSet rs) -> {
            Assignment a = assignment(rs);
            map.put(BackofficeStore.key(a.kind(), a.ref()), a);
        });
        return map;
    }

    @Override
    public Optional<Assignment> assignment(String kind, String ref) {
        return jdbc.query("SELECT * FROM lead_assignment WHERE lead_kind = ? AND lead_ref = ?", (rs, i) -> assignment(rs), kind, ref)
                .stream().findFirst();
    }

    private static Assignment assignment(ResultSet rs) throws SQLException {
        return new Assignment(rs.getString("lead_kind"), rs.getString("lead_ref"), rs.getString("channel"), rs.getString("status"),
                rs.getString("assignee_label"), rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    @Override
    @Transactional
    public Assignment saveAssignment(String kind, String ref, String channel, String status, String assigneeLabel, String note) {
        jdbc.update("""
                INSERT INTO lead_assignment (lead_kind, lead_ref, channel, status, assignee_label, note) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (lead_kind, lead_ref) DO UPDATE SET channel = EXCLUDED.channel, status = EXCLUDED.status,
                    assignee_label = EXCLUDED.assignee_label, note = EXCLUDED.note, updated_at = now()
                """, kind, ref, channel, status, assigneeLabel, note);
        jdbc.update("INSERT INTO lead_assignment_event (lead_kind, lead_ref, channel, status, assignee_label, note) VALUES (?, ?, ?, ?, ?, ?)",
                kind, ref, channel, status, assigneeLabel, note);
        return assignment(kind, ref).orElseThrow();
    }

    @Override
    public List<AssignmentEvent> history(String kind, String ref) {
        return jdbc.query("SELECT * FROM lead_assignment_event WHERE lead_kind = ? AND lead_ref = ? ORDER BY id DESC",
                (rs, i) -> new AssignmentEvent(rs.getString("channel"), rs.getString("status"), rs.getString("assignee_label"),
                        rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class)), kind, ref);
    }

    @Override
    public LiveFunnel liveFunnel() {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM advisor_session) AS sessions,
                       (SELECT count(*) FROM advisor_session WHERE jsonb_typeof(last_recommendation_json) = 'object') AS recommended,
                       (SELECT count(*) FROM advisor_session WHERE order_id IS NOT NULL) AS checkout,
                       (SELECT count(DISTINCT e.order_id) FROM order_event e JOIN customer_order o ON o.id = e.order_id
                         WHERE e.status = 'KYC_VERIFIED' AND o.session_id IS NOT NULL) AS kyc_verified,
                       (SELECT count(*) FROM customer_order WHERE status = 'ACTIVE' AND session_id IS NOT NULL) AS active
                """, (rs, i) -> new LiveFunnel(rs.getInt("sessions"), rs.getInt("recommended"), rs.getInt("checkout"),
                rs.getInt("kyc_verified"), rs.getInt("active")));
    }

    @Override
    public MisSellingReport misSelling() {
        return audit.report();
    }

    private JsonNode read(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON in a back-office read", e);
        }
    }
}
