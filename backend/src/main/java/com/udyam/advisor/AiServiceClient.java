package com.udyam.advisor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Calls the Python ai-service. Any failure (down, timeout, 4xx/5xx, empty body) becomes AiUnavailableException. */
@Component
public class AiServiceClient {

    private final RestClient rest;

    public AiServiceClient(@Qualifier("aiServiceRestClient") RestClient rest) {
        this.rest = rest;
    }

    public AiChatContract.Response chat(AiChatContract.Request request) {
        AiChatContract.Response response;
        try {
            response = rest.post()
                    .uri("/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(AiChatContract.Response.class);
        } catch (RestClientException e) {
            throw new AiUnavailableException("ai-service call failed: " + e.getClass().getSimpleName(), e);
        }
        if (response == null || response.reply() == null || response.stage() == null) {
            throw new AiUnavailableException("ai-service returned an empty response", null);
        }
        return response;
    }

    public static class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
