package edu.kinneret.parking.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link NonceStore}.
 */
class NonceStoreTest {

    @Test
    void shouldRejectDuplicateNonceWithinTtl() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC);
        try (NonceStore nonceStore = new NonceStore(60, fixedClock)) {
            assertTrue(nonceStore.addNonce("nonce-1"));
            assertFalse(nonceStore.addNonce("nonce-1"));
            assertEquals(1, nonceStore.size());
        }
    }

    @Test
    void shouldAllowNonceAfterExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-05-05T12:00:00Z"));
        try (NonceStore nonceStore = new NonceStore(60, clock)) {
            assertTrue(nonceStore.addNonce("nonce-2"));
            clock.setInstant(Instant.parse("2026-05-05T12:01:01Z"));

            assertTrue(nonceStore.addNonce("nonce-2"));
        }
    }

    /**
     * Small mutable clock used by the unit test.
     */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }
    }
}
