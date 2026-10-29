package com.udyam.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** AdvisorStore for unit tests (no DB). */
class InMemoryAdvisorStore implements AdvisorStore {

    final Map<UUID, Session> sessions = new LinkedHashMap<>();
    final Map<UUID, List<Message>> messages = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public void createSession(UUID id, String uiLanguage) {
        OffsetDateTime now = OffsetDateTime.now();
        sessions.put(id, new Session(id, now, now, uiLanguage, null, "COLLECTING", null, null, null));
        messages.put(id, new ArrayList<>());
    }

    @Override
    public Optional<Session> findSession(UUID id) {
        return Optional.ofNullable(sessions.get(id));
    }

    @Override
    public void addMessage(UUID sessionId, String role, String content, JsonNode toolTrace, JsonNode guard) {
        messages.get(sessionId).add(new Message(nextId++, role, content, toolTrace, guard, OffsetDateTime.now()));
    }

    @Override
    public List<Message> messages(UUID sessionId) {
        return List.copyOf(messages.get(sessionId));
    }

    @Override
    public void updateSession(UUID id, String stage, String detectedLanguage, JsonNode profile,
                              JsonNode recommendation, JsonNode quote) {
        Session s = sessions.get(id);
        sessions.put(id, new Session(id, s.createdAt(), OffsetDateTime.now(), s.uiLanguage(), detectedLanguage, stage,
                profile, recommendation, quote));
    }

    @Override
    public void touch(UUID id) {
    }
}
