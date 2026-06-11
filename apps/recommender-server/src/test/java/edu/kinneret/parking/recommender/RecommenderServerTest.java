package edu.kinneret.parking.recommender;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.ParkingRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RecommenderServerTest {
    private AppConfig appConfig;
    private RecommenderServer server;

    @BeforeEach
    public void setUp() {
        ParkingRepository.isDbOnline = false;
        edu.kinneret.parking.common.NonceStore.requireDbOnline = false;
        appConfig = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.CUSTOMER_UI);
        server = new RecommenderServer(
                "recommender-test",
                8099,
                "localhost",
                8099,
                true,
                false,
                List.of("recommender1=localhost:8091", "recommender2=localhost:8092", "recommender3=localhost:8093"),
                appConfig
        );
    }

    @Test
    public void testSerialization() {
        List<RecommendationResult> results = List.of(
                new RecommendationResult("4", 2),
                new RecommendationResult("3", 1)
        );
        assertEquals("3;1, Space 4;2", RecommenderServer.serializeResults(results));
    }

    @Test
    public void requestedSpaceAvailableAndMinimumCitationCount() {
        assertEquals("3;1", serialize("3", candidates(10, 5, 1, 5, 3, 3)));
    }

    @Test
    public void requestedSpaceAvailableAndTiedForMinimumWins() {
        assertEquals("3;3", serialize("3", candidates(10, 5, 3, 5, 3, 3)));
    }

    @Test
    public void requestedSpaceAvailableButNotMinimumChoosesClosestMinimum() {
        assertEquals("5;3", serialize("3", candidates(10, 5, 7, 5, 3, 3)));
    }

    @Test
    public void equalDistanceTieReturnsBothClosestSpaces() {
        assertEquals("2;3, Space 4;3", serialize("3", candidates(10, 3, 7, 3, 5, 3)));
    }

    @Test
    public void zeroCitationTieIncludesRequestedSpaceWhenAvailable() {
        assertEquals("3;0", serialize("3", candidates(0, 0, 0, 0, 0, 0)));
    }

    @Test
    public void requestedSpaceWithCitationChoosesAdjacentZeroCitationSpaces() {
        assertEquals("2;0, Space 4;0", serialize("3", candidates(0, 0, 1, 0, 0, 0)));
    }

    @Test
    public void busyRequestedSpaceChoosesBestAvailableAlternative() {
        List<RecommendationResult> available = List.of(
                new RecommendationResult("1", 2),
                new RecommendationResult("4", 2),
                new RecommendationResult("5", 2),
                new RecommendationResult("6", 3)
        );
        assertEquals("4;2", serialize("3", available));
    }

    @Test
    public void noAvailableSpacesReturnsEmptyList() {
        assertTrue(RecommenderServer.recommendFromCandidates("3", List.of()).isEmpty());
    }

    @Test
    public void multipleSpacesWithSameMinimumCitationsReturnsClosestSubset() {
        List<RecommendationResult> available = List.of(
                new RecommendationResult("1", 10),
                new RecommendationResult("6", 10)
        );
        assertEquals("1;10", serialize("3", available));
    }

    @Test
    public void invalidParkingSpaceInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RecommenderServer.recommendFromCandidates("ABC", candidates(1, 2, 3)));
        assertThrows(IllegalArgumentException.class, () -> RecommenderServer.recommendFromCandidates("101", candidates(1, 2, 3)));
    }

    @Test
    public void maliciousModeReturnsFakedResult() {
        server.setMalicious(true);
        assertTrue(server.calculateLocalRecommendation("3").startsWith("Request: Space 3\nResult: Space 999;999"));
    }

    @Test
    public void offlineRepositoryPathStillFiltersByZone() {
        try (ParkingRepository occupiedRepo = new ParkingRepository(appConfig) {
            @Override
            public Document getLatestTransactionForSpace(String spaceId) {
                if ("13".equals(spaceId)) {
                    return new Document("type", "transaction.start")
                            .append("payload", new Document("action", "start"));
                }
                return null;
            }
        }) {
            String result = server.calculateLocalRecommendation("13", occupiedRepo);
            assertTrue(result.contains("Result: Space 3;0, Space 23;0"), "Expected recommendation to be Space 3;0, Space 23;0 but was: " + result);
        }
    }

    @Test
    public void consensusAllThreeAgreeSucceeds() {
        assertEquals("3;1", consensus("3;1", "3;1", "3;1"));
    }

    @Test
    public void consensusTwoOfThreeAgreeSucceeds() {
        assertEquals("3;1", consensus("3;1", "4;1", "3;1"));
    }

    @Test
    public void consensusThreeDifferentResultsFails() {
        assertEquals(null, consensus("3;1", "4;1", "5;1"));
    }

    @Test
    public void consensusTwoDifferentResultsAndOneMissingFails() {
        assertEquals(null, consensus("3;1", "4;1", null));
    }

    @Test
    public void consensusOnlyLeaderRespondsFails() {
        assertEquals(null, consensus("3;1", null, null));
    }

    @Test
    public void consensusOneMaliciousNodeHonestMajorityWins() {
        assertEquals("3;1", consensus("3;1", "999;999", "3;1"));
    }

    @Test
    public void consensusTwoMaliciousDifferentNodesNoMajorityFails() {
        assertEquals(null, consensus("3;1", "999;999", "998;998"));
    }

    @Test
    public void consensusMissingNodeButRemainingTwoAgreeSucceeds() {
        assertEquals("3;1", consensus("3;1", null, "3;1"));
    }

    @Test
    public void consensusRequiresExactListEquality() {
        assertEquals(null, consensus("3;1, Space 4;1", "3;1", "4;1"));
    }

    private static List<RecommendationResult> candidates(long... citations) {
        java.util.ArrayList<RecommendationResult> results = new java.util.ArrayList<>();
        for (int i = 0; i < citations.length; i++) {
            results.add(new RecommendationResult(String.valueOf(i + 1), citations[i]));
        }
        return results;
    }

    private static String serialize(String desired, List<RecommendationResult> candidates) {
        return RecommenderServer.serializeResults(RecommenderServer.recommendFromCandidates(desired, candidates));
    }

    private static String consensus(String server1, String server2, String server3) {
        Map<String, String> votes = new LinkedHashMap<>();
        votes.put("recommender1", server1);
        votes.put("recommender2", server2);
        votes.put("recommender3", server3);
        return RecommenderServer.determineMajority(votes, 2);
    }
}
