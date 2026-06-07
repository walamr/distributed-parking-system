package edu.kinneret.parking.recommender;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.ParkingRepository;
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
        // Verify it is sorted by space number
        assertEquals("3;1, 4;2", serialized);
    }

    @Test
    public void testMaliciousMode() {
        server.setMalicious(true);
        List<RecommendationResult> results = server.calculateLocalRecommendation("3");
        assertEquals(1, results.size());
        assertEquals("999", results.get(0).spaceId());
        assertEquals(999, results.get(0).citationCount());
    }

    @Test
    public void testNormalModeBranchB() {
        // With an offline database client, all spaces default to 0 citations and available.
        // Therefore, the desired space itself is available and has minimum citations (0).
        // Branch B should apply and return the desired space itself.
        try (ParkingRepository repository = new ParkingRepository(appConfig)) {
            List<RecommendationResult> results = server.calculateLocalRecommendation("3", repository);
            assertEquals(1, results.size());
            assertEquals("3", results.get(0).spaceId());
            assertEquals(0, results.get(0).citationCount());
        }
    }

    @Test
    public void testInvalidSpaceId() {
        assertThrows(IllegalArgumentException.class, () -> {
            server.calculateLocalRecommendation("INVALID_SPACE_NAME");
        });
    }
}
