package com.udyam.advisor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tags each /api request with its caller: "ai-service" when X-Internal-Token matches, else "public".
 * The tool endpoints stay public for the frontend (no login in this prototype); this only identifies and
 * logs the caller. Paths are logged with GSTIN-like segments masked.
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String CALLER_ATTRIBUTE = "udyam.caller";
    public static final String AI_SERVICE = "ai-service";
    public static final String PUBLIC = "public";

    private static final Logger log = LoggerFactory.getLogger(InternalTokenFilter.class);
    private static final Pattern GSTIN_LIKE = Pattern.compile("([0-9A-Za-z]{5})[0-9A-Za-z]{7}([0-9A-Za-z]{3})");

    private final AiServiceProperties props;

    public InternalTokenFilter(AiServiceProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(InternalTokenHeader.NAME);
        String caller = PUBLIC;
        if (header != null) {
            if (props.hasToken() && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                    props.token().getBytes(StandardCharsets.UTF_8))) {
                caller = AI_SERVICE;
            } else {
                log.warn("Invalid {} on {} {}; treated as public", InternalTokenHeader.NAME, request.getMethod(),
                        maskPath(request.getRequestURI()));
            }
        }
        request.setAttribute(CALLER_ATTRIBUTE, caller);
        log.info("API {} {} caller={}", request.getMethod(), maskPath(request.getRequestURI()), caller);
        chain.doFilter(request, response);
    }

    static String maskPath(String path) {
        return GSTIN_LIKE.matcher(path).replaceAll("$1*****$2");
    }
}
