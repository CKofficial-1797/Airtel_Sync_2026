package com.udyam.kyc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udyam.kyc.checks.CheckResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** KycStore on Postgres. */
@Repository
public class JdbcKycStore implements KycStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcKycStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void saveConsent(UUID orderId, String noticeVersion, String language, Map<String, Boolean> purposes) {
        withdrawConsent(orderId);
        purposes.forEach((purpose, granted) -> jdbc.update("""
                INSERT INTO kyc_consent (order_id, purpose, granted, notice_version, language) VALUES (?, ?, ?, ?, ?)
                """, orderId, purpose, granted, noticeVersion, language));
    }

    @Override
    public List<Consent> activeConsent(UUID orderId) {
        return jdbc.query("""
                SELECT purpose, granted, notice_version, language, created_at FROM kyc_consent
                WHERE order_id = ? AND withdrawn_at IS NULL ORDER BY id
                """, (rs, i) -> new Consent(rs.getString("purpose"), rs.getBoolean("granted"),
                rs.getString("notice_version"), rs.getString("language"),
                rs.getObject("created_at", OffsetDateTime.class)), orderId);
    }

    @Override
    public int withdrawConsent(UUID orderId) {
        return jdbc.update("UPDATE kyc_consent SET withdrawn_at = now() WHERE order_id = ? AND withdrawn_at IS NULL",
                orderId);
    }

    @Override
    public void saveDocument(UUID orderId, KycDocument d) {
        jdbc.update("""
                INSERT INTO kyc_document (order_id, doc_type, status, detected_doc_type, file_sha256, blur_score,
                    skew_deg, deskewed, extracted_json, model, uploaded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now())
                ON CONFLICT (order_id, doc_type) DO UPDATE SET status = EXCLUDED.status,
                    detected_doc_type = EXCLUDED.detected_doc_type, file_sha256 = EXCLUDED.file_sha256,
                    blur_score = EXCLUDED.blur_score, skew_deg = EXCLUDED.skew_deg, deskewed = EXCLUDED.deskewed,
                    extracted_json = EXCLUDED.extracted_json, model = EXCLUDED.model, uploaded_at = now()
                """, orderId, d.docType().name(), d.status(), d.detectedDocType(), d.sha256(), d.blurScore(),
                d.skewDeg(), d.deskewed(), d.fields() == null ? null : d.fields().toString(), d.model());
    }

    @Override
    public List<KycDocument> documents(UUID orderId) {
        return jdbc.query("""
                SELECT doc_type, status, detected_doc_type, file_sha256, blur_score, skew_deg, deskewed,
                       extracted_json::text AS fields, model, uploaded_at
                FROM kyc_document WHERE order_id = ? ORDER BY id
                """, (rs, i) -> new KycDocument(DocType.valueOf(rs.getString("doc_type")), rs.getString("status"),
                rs.getString("detected_doc_type"), rs.getString("file_sha256"), number(rs, "blur_score"),
                number(rs, "skew_deg"), rs.getBoolean("deskewed"), readTree(rs.getString("fields")),
                rs.getString("model"), rs.getObject("uploaded_at", OffsetDateTime.class)), orderId);
    }

    @Override
    public void saveChecks(UUID orderId, UUID runId, List<CheckResult> checks) {
        jdbc.batchUpdate("""
                INSERT INTO kyc_check (order_id, run_id, check_code, check_name, status, severity, fixable,
                    message_en, message_hi, fix_hint_en, fix_hint_hi, evidence_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, checks, checks.size(), (ps, c) -> {
                    ps.setObject(1, orderId);
                    ps.setObject(2, runId);
                    ps.setString(3, c.code());
                    ps.setString(4, c.name());
                    ps.setString(5, c.status());
                    ps.setString(6, c.severity());
                    ps.setBoolean(7, c.fixable());
                    ps.setString(8, c.messageEn());
                    ps.setString(9, c.messageHi());
                    ps.setString(10, c.fixHintEn());
                    ps.setString(11, c.fixHintHi());
                    ps.setString(12, c.evidence() == null ? null : write(c.evidence()));
                });
    }

    @Override
    public List<CheckResult> latestChecks(UUID orderId) {
        return jdbc.query("""
                SELECT check_code, check_name, status, severity, fixable, message_en, message_hi, fix_hint_en,
                       fix_hint_hi, evidence_json::text AS evidence
                FROM kyc_check
                WHERE order_id = ? AND run_id = (SELECT run_id FROM kyc_check WHERE order_id = ? ORDER BY id DESC LIMIT 1)
                ORDER BY check_code
                """, (rs, i) -> new CheckResult(rs.getString("check_code"), rs.getString("check_name"),
                rs.getString("status"), rs.getString("severity"), rs.getBoolean("fixable"),
                rs.getString("message_en"), rs.getString("message_hi"), rs.getString("fix_hint_en"),
                rs.getString("fix_hint_hi"), readMap(rs.getString("evidence"))), orderId, orderId);
    }

    @Override
    public void deleteDocumentsAndChecks(UUID orderId) {
        jdbc.update("DELETE FROM kyc_document WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM kyc_check WHERE order_id = ?", orderId);
    }

    @Override
    public void setActivationDate(UUID orderId, LocalDate date) {
        jdbc.update("UPDATE customer_order SET estimated_activation_date = ?, updated_at = now() WHERE id = ?",
                date, orderId);
    }

    @Override
    public Optional<LocalDate> activationDate(UUID orderId) {
        return jdbc.query("SELECT estimated_activation_date FROM customer_order WHERE id = ?",
                (rs, i) -> rs.getObject(1, LocalDate.class), orderId).stream().filter(d -> d != null).findFirst();
    }

    private static Double number(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private JsonNode readTree(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON in kyc_document", e);
        }
    }

    private Map<String, Object> readMap(String text) {
        try {
            return text == null ? null : json.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON in kyc_check", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise check evidence", e);
        }
    }
}
