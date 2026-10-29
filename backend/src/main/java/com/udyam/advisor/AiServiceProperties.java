package com.udyam.advisor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** app.ai-service.* : where the Python advisor runs and the shared X-Internal-Token secret. */
@ConfigurationProperties(prefix = "app.ai-service")
public record AiServiceProperties(
        @DefaultValue("http://localhost:8000") String baseUrl,
        String token,
        @DefaultValue("45000") int timeoutMs) {

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }
}
