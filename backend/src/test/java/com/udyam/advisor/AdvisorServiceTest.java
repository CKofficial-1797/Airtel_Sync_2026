package com.udyam.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.udyam.common.errors.ApiException;
import com.udyam.common.errors.NotFoundException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AdvisorServiceTest {

    private static final String AI = "http://ai.test";
    private static final String TOKEN = "test-token";
    private static final String RECOMMENDED_RESPONSE = """
            {"reply": "Your plan is Rs 1,597 per month. Would you like to change anything, or go ahead?",
             "detectedLanguage": "en",
             "profile": {"businessType": "Kirana store", "city": "Ludhiana", "employees": 3, "sites": 1,
                         "gstinDeclared": false},
             "stage": "RECOMMENDED",
             "recommendation": {"segment": "Micro retail & services", "included": [{"productId": "P01"}]},
             "quote": {"monthlyTotalInr": 1597, "lines": [{"productId": "P01", "quantity": 1}]},
             "toolTrace": [{"tool": "save_profile", "args": {}, "ok": true, "ms": 1},
                           {"tool": "recommend_bundle", "args": {}, "ok": true, "ms": 40}],
             "guard": {"passed": true, "retries": 0, "fallbackUsed": false, "violations": []}}
            """;

    private final ObjectMapper json = new ObjectMapper();
    private InMemoryAdvisorStore store;
    private MockRestServiceServer server;
    private AdvisorService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(AI).defaultHeader(InternalTokenHeader.NAME, TOKEN);
        server = MockRestServiceServer.bindTo(builder).build();
        store = new InMemoryAdvisorStore();
        service = new AdvisorService(store, new AiServiceClient(builder.build()), json);
    }

    @Test
    void createSessionReturnsBilingualGreetingWithoutCallingTheAi() {
        AdvisorService.SessionCreated created = service.createSession("hi");

        assertThat(created.greeting()).contains("Namaste").contains("नमस्ते");
        assertThat(store.sessions.get(created.sessionId()).stage()).isEqualTo("COLLECTING");
        assertThat(store.sessions.get(created.sessionId()).uiLanguage()).isEqualTo("hi");
        assertThat(store.messages.get(created.sessionId())).singleElement()
                .satisfies(m -> assertThat(m.role()).isEqualTo("assistant"));
        server.verify();
    }

    @Test
    void messageRoundTripPersistsBothMessagesAndSessionState() {
        UUID id = service.createSession("en").sessionId();
        String text = "I run a kirana store in Ludhiana with 3 staff, one shop, no GST";
        server.expect(requestTo(AI + "/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(InternalTokenHeader.NAME, TOKEN))
                .andExpect(jsonPath("$.sessionId").value(id.toString()))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].content").value(text))
                .andExpect(jsonPath("$.uiLanguage").value("en"))
                .andExpect(jsonPath("$.state.stage").value("COLLECTING"))
                .andRespond(withSuccess(RECOMMENDED_RESPONSE, MediaType.APPLICATION_JSON));

        AdvisorService.AdvisorReply reply = service.sendMessage(id, text);

        server.verify();
        assertThat(reply.stage()).isEqualTo("RECOMMENDED");
        assertThat(reply.quote().get("monthlyTotalInr").asInt()).isEqualTo(1597);
        assertThat(reply.profile().get("city").asText()).isEqualTo("Ludhiana");

        AdvisorStore.Session session = store.sessions.get(id);
        assertThat(session.stage()).isEqualTo("RECOMMENDED");
        assertThat(session.detectedLanguage()).isEqualTo("en");
        assertThat(session.profile().get("employees").asInt()).isEqualTo(3);
        assertThat(session.recommendation().get("included").get(0).get("productId").asText()).isEqualTo("P01");
        assertThat(store.messages.get(id)).extracting(AdvisorStore.Message::role)
                .containsExactly("assistant", "user", "assistant");
        AdvisorStore.Message last = store.messages.get(id).get(2);
        assertThat(last.toolTrace().size()).isEqualTo(2);
        assertThat(last.guard().get("passed").asBoolean()).isTrue();
    }

    @Test
    void nextTurnSendsTheStoredStateBack() throws IOException {
        UUID id = service.createSession(null).sessionId();
        server.expect(requestTo(AI + "/chat")).andRespond(withSuccess(RECOMMENDED_RESPONSE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(AI + "/chat"))
                .andExpect(jsonPath("$.state.stage").value("RECOMMENDED"))
                .andExpect(jsonPath("$.state.quote.monthlyTotalInr").value(1597))
                .andExpect(jsonPath("$.profile.city").value("Ludhiana"))
                .andExpect(jsonPath("$.messages.length()").value(4))
                .andRespond(withSuccess(RECOMMENDED_RESPONSE.replace("\"RECOMMENDED\"", "\"READY_FOR_CHECKOUT\""),
                        MediaType.APPLICATION_JSON));

        service.sendMessage(id, "kirana Ludhiana 3 staff 1 shop no GST");
        AdvisorService.AdvisorReply second = service.sendMessage(id, "yes, go ahead");

        server.verify();
        assertThat(second.stage()).isEqualTo("READY_FOR_CHECKOUT");
        assertThat(service.getSession(id).messages()).hasSize(5);
    }

    @Test
    void aiServiceErrorGives503AndKeepsTheUserMessage() {
        UUID id = service.createSession("en").sessionId();
        server.expect(requestTo(AI + "/chat")).andRespond(withServerError());

        assertThatThrownBy(() -> service.sendMessage(id, "hello"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("AI_UNAVAILABLE");
                    assertThat(e.getStatus().value()).isEqualTo(503);
                });
        assertThat(store.messages.get(id)).extracting(AdvisorStore.Message::content).containsExactly(
                AdvisorService.GREETING, "hello");
        assertThat(store.sessions.get(id).stage()).isEqualTo("COLLECTING");
    }

    @Test
    void aiServiceTimeoutGives503() {
        UUID id = service.createSession("en").sessionId();
        server.expect(requestTo(AI + "/chat")).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> service.sendMessage(id, "hello"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("AI_UNAVAILABLE"));
        assertThat(store.messages.get(id)).hasSize(2);
    }

    @Test
    void unknownStageFromAiIsTreatedAsUnavailable() {
        UUID id = service.createSession("en").sessionId();
        server.expect(requestTo(AI + "/chat")).andRespond(withSuccess(
                RECOMMENDED_RESPONSE.replace("\"RECOMMENDED\"", "\"PAID\""), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.sendMessage(id, "hello")).isInstanceOf(ApiException.class);
        assertThat(store.sessions.get(id).stage()).isEqualTo("COLLECTING");
    }

    @Test
    void unknownSessionIs404() {
        assertThatThrownBy(() -> service.sendMessage(UUID.randomUUID(), "hi")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getSession(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }
}
