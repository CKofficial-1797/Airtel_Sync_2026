package com.udyam.backoffice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A prototype gate, not real authentication: one shared passcode in X-Backoffice-Key, no users, no audit of who did what.
 * Everything under /api/backoffice needs it, and with no key configured nothing gets in.
 */
@Component
public class BackofficeKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Backoffice-Key";

    private final BackofficeProperties props;

    public BackofficeKeyFilter(BackofficeProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/backoffice") || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        if (props.hasKey() && given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                props.key().getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"code\":\"BACKOFFICE_KEY_REQUIRED\","
                + "\"message\":\"A valid back-office key is required\"}");
    }
}
