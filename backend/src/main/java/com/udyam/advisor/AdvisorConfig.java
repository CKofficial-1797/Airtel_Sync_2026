package com.udyam.advisor;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AdvisorConfig {

    private static final Logger log = LoggerFactory.getLogger(AdvisorConfig.class);

    /** RestClient for the ai-service: base URL, timeouts and the X-Internal-Token header. */
    @Bean
    public RestClient aiServiceRestClient(RestClient.Builder builder, AiServiceProperties props) {
        if (!props.hasToken()) {
            log.warn("app.ai-service.token is not set: the ai-service will reject advisor calls (401)");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofMillis(props.timeoutMs()));
        return builder.clone()
                .requestFactory(factory)
                .baseUrl(props.baseUrl())
                .defaultHeader(InternalTokenHeader.NAME, props.hasToken() ? props.token() : "")
                .build();
    }
}
