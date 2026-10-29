package com.udyam.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** JSON contract of the ai-service POST /chat (see ai-service/app/models.py). */
public final class AiChatContract {

    private AiChatContract() {
    }

    public record Message(String role, String content) {
    }

    /** What Spring remembers between turns and hands back to the stateless ai-service. */
    public record State(String stage, JsonNode recommendation, JsonNode quote) {
    }

    public record Request(String sessionId, List<Message> messages, JsonNode profile, String uiLanguage,
                          State state) {
    }

    public record Response(String reply, String detectedLanguage, JsonNode profile, String stage,
                           JsonNode recommendation, JsonNode quote, JsonNode toolTrace, JsonNode guard) {
    }
}
