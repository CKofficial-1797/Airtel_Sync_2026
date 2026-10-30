package com.udyam.tracker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stops guessing on the order lookup: at most 10 attempts per IP in 10 minutes. In memory, so it resets on restart
 * and doesn't span instances: enough for this prototype. Every attempt counts, right or wrong.
 */
@Component
public class LookupRateLimiter {

    static final int MAX_ATTEMPTS = 10;
    static final Duration WINDOW = Duration.ofMinutes(10);

    private final Clock clock;
    private final Map<String, Deque<Instant>> attempts = new HashMap<>();

    @Autowired
    public LookupRateLimiter() {
        this(Clock.systemUTC());
    }

    LookupRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Records the attempt; false when this key is over the limit. */
    public synchronized boolean tryAcquire(String key) {
        Instant now = clock.instant();
        attempts.values().forEach(q -> prune(q, now));
        attempts.values().removeIf(Deque::isEmpty);
        Deque<Instant> recent = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        if (recent.size() >= MAX_ATTEMPTS) {
            return false;
        }
        recent.addLast(now);
        return true;
    }

    private static void prune(Deque<Instant> q, Instant now) {
        while (!q.isEmpty() && !q.peekFirst().isAfter(now.minus(WINDOW))) {
            q.removeFirst();
        }
    }
}
