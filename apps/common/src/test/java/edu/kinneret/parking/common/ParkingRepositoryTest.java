package edu.kinneret.parking.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests the safe-by-default behaviour of {@link ParkingRepository#countCitationsForSpace(String)}.
 *
 * <p>These cover the guard paths that must never touch the database or throw: a blank/absent space
 * id always counts as zero citations. The exact-space and nested {@code payload.spaceId} matching
 * is exercised against a live MongoDB by the recommender/CLI integration paths that share the same
 * {@code citations} filter.
 */
class ParkingRepositoryTest {

    private static AppConfig testConfig() {
        return AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.MO_UI,
                Map.of(
                        "RABBITMQ_PASSWORD", "test-pass",
                        "MONGO_PASSWORD", "test-pass",
                        "HMAC_SECRET", "test-secret-1234567890"));
    }

    @Test
    void countCitationsForSpaceReturnsZeroForBlankInput() {
        try (ParkingRepository repository = new ParkingRepository(testConfig())) {
            assertEquals(0L, repository.countCitationsForSpace(null));
            assertEquals(0L, repository.countCitationsForSpace(""));
            assertEquals(0L, repository.countCitationsForSpace("   "));
        }
    }
}
