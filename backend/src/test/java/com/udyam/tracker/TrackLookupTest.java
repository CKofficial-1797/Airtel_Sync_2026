package com.udyam.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udyam.common.errors.ApiException;
import com.udyam.common.errors.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TrackLookupTest {

    private static final String K03_PIN = "411231";

    private final TrackerKit kit = new TrackerKit();
    private final TrackLookupService lookup = new TrackLookupService(kit.orders, kit.kycStore, new LookupRateLimiter());

    private String ref(UUID id) {
        return kit.orders.find(id).orElseThrow().orderRef();
    }

    @Test
    void theRightReferenceAndPincodeFindTheOrder() {
        UUID id = kit.afterKyc("K03");
        assertThat(lookup.lookup(ref(id), K03_PIN, "ip")).isEqualTo(id);
        assertThat(lookup.lookup("  " + ref(id).toLowerCase() + " ", " " + K03_PIN + " ", "ip")).isEqualTo(id);
    }

    @Test
    void everyWrongInputGetsTheSameAnswer() {
        UUID id = kit.afterKyc("K03");
        String[] messages = {
                messageOf(ref(id), "999999"),
                messageOf("UD-260715-9999", K03_PIN),
                messageOf(ref(id), ""),
                messageOf(null, null),
        };
        assertThat(messages).containsOnly("Order not found");
    }

    private String messageOf(String orderRef, String pin) {
        try {
            lookup.lookup(orderRef, pin, "ip");
            throw new AssertionError("Expected a 404");
        } catch (NotFoundException e) {
            return e.getMessage();
        }
    }

    @Test
    void anOrderWithoutAnAddressYetCantBeFound() {
        UUID id = kit.newOrder();
        assertThatThrownBy(() -> lookup.lookup(ref(id), K03_PIN, "ip")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void afterAWithdrawalThePincodeIsGoneSoTheLookupFails() {
        UUID id = kit.afterKyc("K03");
        kit.kyc.withdrawConsent(id);
        assertThatThrownBy(() -> lookup.lookup(ref(id), K03_PIN, "ip")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void the11thAttemptIsRateLimitedEvenWithTheRightAnswer() {
        UUID id = kit.afterKyc("K03");
        for (int i = 0; i < LookupRateLimiter.MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> lookup.lookup(ref(id), "000000", "ip")).isInstanceOf(NotFoundException.class);
        }
        assertThatThrownBy(() -> lookup.lookup(ref(id), K03_PIN, "ip")).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(e.getCode()).isEqualTo("RATE_LIMITED");
        });
        assertThat(lookup.lookup(ref(id), K03_PIN, "another-ip")).isEqualTo(id);
    }

    /** A clock the test can move. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-07-15T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void theLimitLiftsAfterTheWindow() {
        MovableClock clock = new MovableClock();
        LookupRateLimiter limiter = new LookupRateLimiter(clock);
        for (int i = 0; i < LookupRateLimiter.MAX_ATTEMPTS; i++) {
            assertThat(limiter.tryAcquire("ip")).isTrue();
        }
        assertThat(limiter.tryAcquire("ip")).isFalse();

        clock.advance(LookupRateLimiter.WINDOW.minusSeconds(1));
        assertThat(limiter.tryAcquire("ip")).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("ip")).isTrue();
    }
}
