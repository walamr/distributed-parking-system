package edu.kinneret.parking.recommender;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.ParkingRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RecommenderServerTest {
    private AppConfig appConfig;
    private RecommenderServer server;

    @BeforeEach
    public void setUp() {
        ParkingRepository.isDbOnline = false;
        appConfig = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.CUSTOMER_UI);
        server = new RecommenderServer(
                "recommender-test",
                8099,
                "localhost",
                8099,
                true,
                false,
                Collections.emptyList(),
                appConfig
        );
    }

    @Test
    public void testSerialization() {
        List<RecommendationResult> results = Arrays.asList(
                new RecommendationResult("4", 2),
                new RecommendationResult("3", 1)
        );
        String serialized = RecommenderServer.serializeResults(results);
        // Verify it returns a formatted comma-separated list
        assertEquals("3;1, Space 4;2", serialized);
    }

    @Test
    public void testMaliciousMode() {
        server.setMalicious(true);
        String result = server.calculateLocalRecommendation("3");
        assertTrue(result.startsWith("Request: Space 3\nResult: Space 999;999"));
    }

    @Test
    public void testNormalModeBranchB() {
        // With an offline database client, all spaces default to 0 citations and available.
        // Therefore, the desired space itself is available and has minimum citations (0).
        // Branch B should apply and return the desired space itself.
        try (ParkingRepository repository = new ParkingRepository(appConfig)) {
            String result = server.calculateLocalRecommendation("3", repository);
            assertTrue(result.startsWith("Request: Space 3\nResult: Space 3;0"));
        }
    }

    @Test
    public void testZoneFilteringAndBranchA() {
        try (ParkingRepository occupiedRepo = new ParkingRepository(appConfig) {
            @Override
            public Document getLatestTransactionForSpace(String spaceId) {
                if ("3".equals(spaceId)) {
                    return new Document("type", "transaction.start")
                            .append("payload", new Document("action", "start"));
                }
                return null;
            }
        }) {
            String result = server.calculateLocalRecommendation("3", occupiedRepo);
            // Space 3 is in zone "Fifth Dr". Space 3 is occupied.
            // Under SUC 8, only other spaces in "Fifth Dr" (13, 23, 33, ...) should be considered.
            // Since all have 0 citations, the nearest available space in zone is 13.
            // (Without zone filtering, the nearest available would be 2 or 4).
            assertTrue(result.contains("Result: Space 13;0"), "Expected recommendation to be Space 13;0 but was: " + result);
        }
    }

    @Test
    public void testInvalidSpaceId() {
        assertThrows(IllegalArgumentException.class, () -> {
            server.calculateLocalRecommendation("INVALID_SPACE_NAME");
        });
    }
}
