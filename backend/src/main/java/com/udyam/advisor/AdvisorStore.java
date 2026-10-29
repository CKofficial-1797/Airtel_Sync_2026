package com.udyam.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for advisor sessions/messages. App: JdbcAdvisorStore (Neon); tests: an in-memory version. */
public interface AdvisorStore {

    void createSession(UUID id, String uiLanguage);

    Optional<Session> findSession(UUID id);

    void addMessage(UUID sessionId, String role, String content, JsonNode toolTrace, JsonNode guard);

    List<Message> messages(UUID sessionId);

    void updateSession(UUID id, String stage, String detectedLanguage, JsonNode profile, JsonNode recommendation,
                       JsonNode quote);

    void touch(UUID id);

    record Session(UUID id, OffsetDateTime createdAt, OffsetDateTime updatedAt, String uiLanguage,
                   String detectedLanguage, String stage, JsonNode profile, JsonNode recommendation, JsonNode quote) {
    }

    record Message(long id, String role, String content, JsonNode toolTrace, JsonNode guard,
                   OffsetDateTime createdAt) {
    }
}
