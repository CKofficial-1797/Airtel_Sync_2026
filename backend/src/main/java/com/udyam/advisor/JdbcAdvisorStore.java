package com.udyam.advisor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** AdvisorStore on Postgres (JSONB columns written as text with ::jsonb casts). */
@Repository
public class JdbcAdvisorStore implements AdvisorStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcAdvisorStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void createSession(UUID id, String uiLanguage) {
        jdbc.update("INSERT INTO advisor_session (id, ui_language) VALUES (?, ?)", id, uiLanguage);
    }

    @Override
    public Optional<Session> findSession(UUID id) {
        return jdbc.query("""
                SELECT id, created_at, updated_at, ui_language, detected_language, stage,
                       profile_json::text AS profile, last_recommendation_json::text AS recommendation,
                       last_quote_json::text AS quote
                FROM advisor_session WHERE id = ?
                """, (rs, i) -> new Session(rs.getObject("id", UUID.class), time(rs, "created_at"),
                time(rs, "updated_at"), rs.getString("ui_language"), rs.getString("detected_language"),
                rs.getString("stage"), read(rs.getString("profile")), read(rs.getString("recommendation")),
                read(rs.getString("quote"))), id).stream().findFirst();
    }

    @Override
    public void addMessage(UUID sessionId, String role, String content, JsonNode toolTrace, JsonNode guard) {
        jdbc.update("""
                INSERT INTO advisor_message (session_id, role, content, tool_trace_json, guard_json)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb)
                """, sessionId, role, content, write(toolTrace), write(guard));
        touch(sessionId);
    }

    @Override
    public List<Message> messages(UUID sessionId) {
        return jdbc.query("""
                SELECT id, role, content, tool_trace_json::text AS trace, guard_json::text AS guard, created_at
                FROM advisor_message WHERE session_id = ? ORDER BY id
                """, (rs, i) -> new Message(rs.getLong("id"), rs.getString("role"), rs.getString("content"),
                read(rs.getString("trace")), read(rs.getString("guard")), time(rs, "created_at")), sessionId);
    }

    @Override
    public void updateSession(UUID id, String stage, String detectedLanguage, JsonNode profile,
                              JsonNode recommendation, JsonNode quote) {
        jdbc.update("""
                UPDATE advisor_session
                SET stage = ?, detected_language = ?, profile_json = COALESCE(?::jsonb, '{}'::jsonb),
                    last_recommendation_json = ?::jsonb, last_quote_json = ?::jsonb, updated_at = now()
                WHERE id = ?
                """, stage, detectedLanguage, write(profile), write(recommendation), write(quote), id);
    }

    @Override
    public void touch(UUID id) {
        jdbc.update("UPDATE advisor_session SET updated_at = now() WHERE id = ?", id);
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    private JsonNode read(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON in advisor tables", e);
        }
    }

    private String write(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }
}
