package com.udyam.backoffice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BackofficeKeyFilterTest {

    private static int statusFor(String configured, String method, String path, String given) throws Exception {
        BackofficeKeyFilter filter = new BackofficeKeyFilter(new BackofficeProperties(configured, 30, 24));
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        if (given != null) {
            request.addHeader(BackofficeKeyFilter.HEADER, given);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void noKeyOrAWrongKeyIsRefused() throws Exception {
        assertThat(statusFor("s3cret", "GET", "/api/backoffice/queue", null)).isEqualTo(401);
        assertThat(statusFor("s3cret", "GET", "/api/backoffice/queue", "wrong")).isEqualTo(401);
        assertThat(statusFor("s3cret", "POST", "/api/backoffice/leads/SESSION/x/assign", "")).isEqualTo(401);
    }

    @Test
    void theRightKeyPasses() throws Exception {
        assertThat(statusFor("s3cret", "GET", "/api/backoffice/queue", "s3cret")).isEqualTo(200);
    }

    @Test
    void withNoKeyConfiguredNothingGetsIn() throws Exception {
        assertThat(statusFor(null, "GET", "/api/backoffice/queue", "anything")).isEqualTo(401);
        assertThat(statusFor("", "GET", "/api/backoffice/queue", "")).isEqualTo(401);
        assertThat(statusFor("  ", "GET", "/api/backoffice/queue", "  ")).isEqualTo(401);
    }

    @Test
    void theRestOfTheApiIsUntouched() throws Exception {
        assertThat(statusFor("s3cret", "GET", "/api/orders/x", null)).isEqualTo(200);
    }

    @Test
    void theRefusalIsJsonAndNamesTheCode() throws Exception {
        BackofficeKeyFilter filter = new BackofficeKeyFilter(new BackofficeProperties("s3cret", 30, 24));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/backoffice/insights");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("BACKOFFICE_KEY_REQUIRED").doesNotContain("s3cret");
    }
}
