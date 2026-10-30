package com.udyam.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udyam.common.errors.ApiException;
import com.udyam.common.errors.NotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Advisor conversations. Spring owns the state (history, profile, stage, last recommendation/quote) and sends
 * all of it to the stateless ai-service on every turn. Logs ids, stages and timings only, never message text.
 */
@Service
public class AdvisorService {

    public static final String GREETING = """
            Namaste! I'm your Accel Business advisor. Tell me about your business: what you do, your city, \
            how many people work there and how many shops or offices you have. I'll suggest the right plan. \
            A member of our team may contact you about your enquiry, and may read this chat to help you.
            नमस्ते! मैं आपका Accel Business सलाहकार हूँ। अपने व्यवसाय के बारे में बताइए: आप क्या करते हैं, \
            किस शहर में हैं, कितने लोग काम करते हैं और कितनी दुकानें या ऑफ़िस हैं। मैं आपके लिए सही प्लान सुझाऊँगा। \
            हमारी टीम का कोई सदस्य आपकी पूछताछ के बारे में आपसे संपर्क कर सकता है, और आपकी मदद के लिए यह चैट पढ़ सकता है।""";

    /** Set by OrderService when the quote is accepted; sticky for the rest of the session. */
    public static final String CHECKOUT_DONE = "CHECKOUT_DONE";

    static final Set<String> STAGES =
            Set.of("COLLECTING", "RECOMMENDED", "CUSTOMISING", "READY_FOR_CHECKOUT", "OUT_OF_SCOPE");

    private static final Logger log = LoggerFactory.getLogger(AdvisorService.class);

    private final AdvisorStore store;
    private final AiServiceClient ai;
    private final ObjectMapper json;

    public AdvisorService(AdvisorStore store, AiServiceClient ai, ObjectMapper json) {
        this.store = store;
        this.ai = ai;
        this.json = json;
    }

    public SessionCreated createSession(String uiLanguage) {
        UUID id = UUID.randomUUID();
        store.createSession(id, uiLanguage);
        store.addMessage(id, "assistant", GREETING, null, null);
        log.info("Advisor session {} created (ui={})", shortId(id), uiLanguage);
        return new SessionCreated(id, GREETING);
    }

    public AdvisorReply sendMessage(UUID sessionId, String text) {
        AdvisorStore.Session session = require(sessionId);
        // After checkout the session stays CHECKOUT_DONE; the ai-service only knows the 5 conversation stages.
        boolean checkedOut = CHECKOUT_DONE.equals(session.stage());
        String aiStage = checkedOut ? "READY_FOR_CHECKOUT" : session.stage();
        // A retry after AI_UNAVAILABLE re-sends a message that is already stored and unanswered: don't duplicate it.
        List<AdvisorStore.Message> stored = store.messages(sessionId);
        AdvisorStore.Message last = stored.isEmpty() ? null : stored.get(stored.size() - 1);
        boolean isRetry = last != null && "user".equals(last.role()) && last.content().equals(text);
        if (!isRetry) {
            store.addMessage(sessionId, "user", text, null, null);
        }

        List<AiChatContract.Message> history = store.messages(sessionId).stream()
                .map(m -> new AiChatContract.Message(m.role(), m.content()))
                .toList();
        AiChatContract.Request request = new AiChatContract.Request(sessionId.toString(), history,
                session.profile() == null ? json.createObjectNode() : session.profile(), session.uiLanguage(),
                new AiChatContract.State(aiStage, session.recommendation(), session.quote()));

        long start = System.currentTimeMillis();
        AiChatContract.Response response;
        try {
            response = ai.chat(request);
            if (!STAGES.contains(response.stage())) {
                throw new AiServiceClient.AiUnavailableException("Unknown stage from ai-service", null);
            }
        } catch (AiServiceClient.AiUnavailableException e) {
            log.warn("Advisor session {}: ai-service unavailable ({})", shortId(sessionId), e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                    "The advisor is temporarily unavailable. Your message was saved; please try again.");
        }

        String stage = checkedOut ? CHECKOUT_DONE : response.stage();
        store.addMessage(sessionId, "assistant", response.reply(), response.toolTrace(), response.guard());
        store.updateSession(sessionId, stage, response.detectedLanguage(), response.profile(),
                response.recommendation(), response.quote());
        log.info("Advisor session {}: stage {} -> {} in {} ms", shortId(sessionId), session.stage(), stage,
                System.currentTimeMillis() - start);
        return new AdvisorReply(sessionId, response.reply(), stage, response.profile(),
                response.recommendation(), response.quote(), response.detectedLanguage());
    }

    public SessionView getSession(UUID sessionId) {
        AdvisorStore.Session s = require(sessionId);
        List<MessageView> messages = store.messages(sessionId).stream()
                .map(m -> new MessageView(m.role(), m.content(), m.createdAt()))
                .toList();
        return new SessionView(s.id(), s.createdAt(), s.updatedAt(), s.uiLanguage(), s.detectedLanguage(),
                s.stage(), s.profile(), s.recommendation(), s.quote(), messages);
    }

    private AdvisorStore.Session require(UUID id) {
        return store.findSession(id).orElseThrow(() -> new NotFoundException("Advisor session not found: " + id));
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    public record SessionCreated(UUID sessionId, String greeting) {
    }

    public record AdvisorReply(UUID sessionId, String reply, String stage, JsonNode profile, JsonNode recommendation,
                               JsonNode quote, String detectedLanguage) {
    }

    public record MessageView(String role, String content, OffsetDateTime createdAt) {
    }

    public record SessionView(UUID sessionId, OffsetDateTime createdAt, OffsetDateTime updatedAt, String uiLanguage,
                              String detectedLanguage, String stage, JsonNode profile, JsonNode recommendation,
                              JsonNode quote, List<MessageView> messages) {
    }
}
