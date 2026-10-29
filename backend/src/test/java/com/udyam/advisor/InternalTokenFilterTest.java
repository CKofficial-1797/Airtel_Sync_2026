package com.udyam.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalTokenFilterTest {

    private final InternalTokenFilter filter = new InternalTokenFilter(new AiServiceProperties("http://x", "s3cret", 1000));

    private String callerFor(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/recommend");
        if (token != null) {
            request.addHeader(InternalTokenHeader.NAME, token);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull(); // never blocks: endpoints stay public
        return (String) request.getAttribute(InternalTokenFilter.CALLER_ATTRIBUTE);
    }

    @Test
    void identifiesTheCaller() throws Exception {
        assertThat(callerFor("s3cret")).isEqualTo(InternalTokenFilter.AI_SERVICE);
        assertThat(callerFor(null)).isEqualTo(InternalTokenFilter.PUBLIC);
        assertThat(callerFor("wrong")).isEqualTo(InternalTokenFilter.PUBLIC);
    }

    @Test
    void masksGstinsInLoggedPaths() {
        assertThat(InternalTokenFilter.maskPath("/api/mock/gstin/32TTNXB7384T1ZW"))
                .isEqualTo("/api/mock/gstin/32TTN*****1ZW");
        assertThat(InternalTokenFilter.maskPath("/api/recommend")).isEqualTo("/api/recommend");
    }
}
