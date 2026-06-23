package edu.kinneret.parking.recommender;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.ParkingRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**

 * Represents a class RecommenderServerTest.

 */

public class RecommenderServerTest {
    private AppConfig appConfig;
    private RecommenderServer server;
    /**
     * Set up.
     */

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
                appConfig,
                new edu.kinneret.parking.common.NonceStore(60)
        );
    }

    /** Closes test-owned resources after every test. */
    @AfterEach
    public void tearDown() {
        server.close();
    }
    /**
     * Test serialization.
     */

    @Test
    public void testSerialization() {
        List<RecommendationResult> results = List.of(
                new RecommendationResult("4", 2),
                new RecommendationResult("3", 1)
        );
        assertEquals("3;1, Space 4;2", RecommenderServer.serializeResults(results));
    }

    /**
     * When every space in the zone is occupied, all nodes vote "Empty List" and consensus must
     * resolve to "Empty List" (rather than failing), so the customer can be told all spaces are full.
     */
    @Test
    public void allOccupiedReachesEmptyListConsensus() {
        java.util.Map<String, String> votes = new java.util.HashMap<>();
        votes.put("recommender1", "Request: Space 5\nResult: Empty List");
        votes.put("recommender2", "Request: Space 5\nResult: Empty List");
        votes.put("recommender3", "Request: Space 5\nResult: Empty List");

        assertEquals("Empty List", RecommenderServer.determineMajority(votes, 2));
    }

    /** A full zone is detected from the "Empty List" consensus; a real space list is not. */
    @Test
    public void zoneFullConsensusIsDetected() {
        org.junit.jupiter.api.Assertions.assertTrue(
                RecommenderServer.isZoneFullConsensus("Empty List"));
        org.junit.jupiter.api.Assertions.assertTrue(
                RecommenderServer.isZoneFullConsensus("Request: Space 5\nResult: Empty List"));
        org.junit.jupiter.api.Assertions.assertFalse(
                RecommenderServer.isZoneFullConsensus("3;0, Space 4;0"));
        org.junit.jupiter.api.Assertions.assertFalse(
                RecommenderServer.isZoneFullConsensus(null));
    }
    /**
     * Requested space available and minimum citation count.
     */

    @Test
    public void requestedSpaceAvailableAndMinimumCitationCount() {
        assertEquals("3;1", serialize("3", candidates(10, 5, 1, 5, 3, 3)));
    }
    /**
     * Requested space available and tied for minimum wins.
     */

    @Test
    public void requestedSpaceAvailableAndTiedForMinimumWins() {
        assertEquals("3;3", serialize("3", candidates(10, 5, 3, 5, 3, 3)));
    }
    /**
     * Requested space available but not minimum chooses closest minimum.
     */

    @Test
    public void requestedSpaceAvailableButNotMinimumChoosesClosestMinimum() {
        assertEquals("5;3", serialize("3", candidates(10, 5, 7, 5, 3, 3)));
    }
    /**
     * Equal distance tie returns both closest spaces.
     */

    @Test
    public void equalDistanceTieReturnsBothClosestSpaces() {
        assertEquals("2;3, Space 4;3", serialize("3", candidates(10, 3, 7, 3, 5, 3)));
    }
    /**
     * Zero citation tie includes requested space when available.
     */

    @Test
    public void zeroCitationTieIncludesRequestedSpaceWhenAvailable() {
        assertEquals("3;0", serialize("3", candidates(0, 0, 0, 0, 0, 0)));
    }
    /**
     * Requested space with citation chooses adjacent zero citation spaces.
     */

    @Test
    public void requestedSpaceWithCitationChoosesAdjacentZeroCitationSpaces() {
        assertEquals("2;0, Space 4;0", serialize("3", candidates(0, 0, 1, 0, 0, 0)));
    }
    /**
     * Busy requested space chooses best available alternative.
     */

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
    /**
     * No available spaces returns empty list.
     */

    @Test
    public void noAvailableSpacesReturnsEmptyList() {
        assertTrue(RecommenderServer.recommendFromCandidates("3", List.of()).isEmpty());
    }

    /** PDF image example 1: requested space is the unique minimum. */
    @Test
    public void pdfExample01() {
        assertEquals("3;1", serialize("3", available(new long[]{10, 5, 1, 5, 3, 3}, new int[]{})));
    }

    /** PDF image example 2: requested space wins a minimum-citation tie. */
    @Test
    public void pdfExample02() {
        assertEquals("3;3", serialize("3", available(new long[]{10, 5, 3, 5, 3, 3}, new int[]{})));
    }

    /** PDF image example 3: nearest minimum is space 5. */
    @Test
    public void pdfExample03() {
        assertEquals("5;3", serialize("3", available(new long[]{10, 5, 7, 5, 3, 3}, new int[]{})));
    }

    /** PDF image example 4: equally near minimum spaces are both returned. */
    @Test
    public void pdfExample04() {
        assertEquals("2;3, Space 4;3", serialize("3", available(new long[]{10, 3, 7, 3, 5, 3}, new int[]{})));
    }

    /** PDF image example 5: requested space wins the tie. */
    @Test
    public void pdfExample05() {
        assertEquals("3;3", serialize("3", available(new long[]{10, 3, 3, 3, 5, 3}, new int[]{})));
    }

    /**
     * PDF image example 6. The slide prints 3;3 even though its table gives
     * space 3 zero citations; the algorithm and citation-display requirement
     * therefore produce the internally consistent result 3;0.
     */
    @Test
    public void pdfExample06() {
        assertEquals("3;0", serialize("3", available(new long[]{0, 0, 0, 0, 0, 0}, new int[]{})));
    }

    /** PDF image example 7: nearest zero-citation spaces bracket the request. */
    @Test
    public void pdfExample07() {
        assertEquals("2;0, Space 4;0", serialize("3", available(new long[]{0, 0, 1, 0, 0, 0}, new int[]{})));
    }

    /** PDF image example 8: the nearest unique minimum is space 2. */
    @Test
    public void pdfExample08() {
        assertEquals("2;1", serialize("3", available(new long[]{2, 1, 2, 2, 2, 3}, new int[]{})));
    }

    /** PDF image example 9: busy space 2 leaves requested space 3 as best. */
    @Test
    public void pdfExample09() {
        assertEquals("3;2", serialize("3", available(new long[]{2, 1, 2, 2, 2, 3}, new int[]{2})));
    }

    /** PDF image example 10: busy spaces 2 and 3 make space 4 nearest. */
    @Test
    public void pdfExample10() {
        assertEquals("4;2", serialize("3", available(new long[]{2, 1, 2, 2, 2, 3}, new int[]{2, 3})));
    }

    /** PDF image example 11: equally near available spaces 1 and 5 are returned. */
    @Test
    public void pdfExample11() {
        assertEquals("1;2, Space 5;2", serialize("3", available(new long[]{2, 1, 2, 2, 2, 3}, new int[]{2, 3, 4})));
    }

    /** PDF image example 12: only space 1 remains available. */
    @Test
    public void pdfExample12() {
        assertEquals("1;10", serialize("3", available(new long[]{10, 1, 2, 2, 2, 3}, new int[]{2, 3, 4, 5, 6})));
    }

    /** PDF image example 13: no available spaces yields an empty list. */
    @Test
    public void pdfExample13() {
        assertTrue(RecommenderServer.recommendFromCandidates("3",
                available(new long[]{2, 1, 2, 2, 2, 3}, new int[]{1, 2, 3, 4, 5, 6})).isEmpty());
    }
    /**
     * Multiple spaces with same minimum citations returns closest subset.
     */

    @Test
    public void multipleSpacesWithSameMinimumCitationsReturnsClosestSubset() {
        List<RecommendationResult> available = List.of(
                new RecommendationResult("1", 10),
                new RecommendationResult("6", 10)
        );
        assertEquals("1;10", serialize("3", available));
    }
    /**
     * Invalid parking space input is rejected.
     */

    @Test
    public void invalidParkingSpaceInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RecommenderServer.recommendFromCandidates("ABC", candidates(1, 2, 3)));
        assertThrows(IllegalArgumentException.class, () -> RecommenderServer.recommendFromCandidates("101", candidates(1, 2, 3)));
    }
    /**
     * Malicious mode returns faked result.
     */

    @Test
    public void maliciousModeReturnsFakedResult() {
        server.setMalicious(true);
        assertTrue(server.calculateLocalRecommendation("3").contains("Result: Space 999;999"));
    }
    /**
     * Test custom malicious payload.
     */

    @Test
    public void testCustomMaliciousPayload() {
        server.setMalicious(true);
        server.setMaliciousPayload("123;123");
        assertEquals("123;123", server.getMaliciousPayload());
        assertTrue(server.calculateLocalRecommendation("3").contains("Result: Space 123;123"));
    }
    /**
     * Offline repository path still filters by zone.
     */

    @Test
    public void offlineRepositoryPathRecommendsAdjacentSpaces() {
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
    /**
     * Consensus all three agree succeeds.
     */

    @Test
    public void consensusAllThreeAgreeSucceeds() {
        assertEquals("3;1", consensus("3;1", "3;1", "3;1"));
    }
    /**
     * Consensus two of three agree succeeds.
     */

    @Test
    public void consensusTwoOfThreeAgreeSucceeds() {
        assertEquals("3;1", consensus("3;1", "4;1", "3;1"));
    }
    /**
     * Consensus three different results fails.
     */

    @Test
    public void consensusThreeDifferentResultsFails() {
        assertEquals(null, consensus("3;1", "4;1", "5;1"));
    }
    /**
     * Consensus two different results and one missing fails.
     */

    @Test
    public void consensusTwoDifferentResultsAndOneMissingFails() {
        assertEquals(null, consensus("3;1", "4;1", null));
    }
    /**
     * Consensus only leader responds fails.
     */

    @Test
    public void consensusOnlyLeaderRespondsFails() {
        assertEquals(null, consensus("3;1", null, null));
    }
    /**
     * Consensus one malicious node honest majority wins.
     */

    @Test
    public void consensusOneMaliciousNodeHonestMajorityWins() {
        assertEquals("3;1", consensus("3;1", "999;999", "3;1"));
    }
    /**
     * Consensus two malicious different nodes no majority fails.
     */

    @Test
    public void consensusTwoMaliciousDifferentNodesNoMajorityFails() {
        assertEquals(null, consensus("3;1", "999;999", "998;998"));
    }
    /**
     * Consensus missing node but remaining two agree succeeds.
     */

    @Test
    public void consensusMissingNodeButRemainingTwoAgreeSucceeds() {
        assertEquals("3;1", consensus("3;1", null, "3;1"));
    }
    /**
     * Consensus requires exact list equality.
     */

    @Test
    public void consensusRequiresExactListEquality() {
        assertEquals(null, consensus("3;1, Space 4;1", "3;1", "4;1"));
    }

/**

 * Candidates.

 * @param citations the citations

 * @return the list<recommendationresult>

 */

    private static List<RecommendationResult> candidates(long... citations) {
        java.util.ArrayList<RecommendationResult> results = new java.util.ArrayList<>();
        for (int i = 0; i < citations.length; i++) {
            results.add(new RecommendationResult(String.valueOf(i + 1), citations[i]));
        }
        return results;
    }

    /**
     * Builds the available candidate list from a PDF citation row and its busy spaces.
     *
     * @param citations citation count for spaces 1 through n
     * @param busySpaces one-based space numbers marked busy
     * @return candidates not marked busy
     */
    private static List<RecommendationResult> available(long[] citations, int[] busySpaces) {
        java.util.Set<Integer> busy = new java.util.HashSet<>();
        for (int space : busySpaces) {
            busy.add(space);
        }
        java.util.ArrayList<RecommendationResult> results = new java.util.ArrayList<>();
        for (int i = 0; i < citations.length; i++) {
            int space = i + 1;
            if (!busy.contains(space)) {
                results.add(new RecommendationResult(String.valueOf(space), citations[i]));
            }
        }
        return results;
    }

/**

 * Serialize.

 * @param desired the desired

 * @param candidates the candidates

 * @return the string

 */

    private static String serialize(String desired, List<RecommendationResult> candidates) {
        return RecommenderServer.serializeResults(RecommenderServer.recommendFromCandidates(desired, candidates));
    }

/**

 * Consensus.

 * @param server1 the server1

 * @param server2 the server2

 * @param server3 the server3

 * @return the string

 */

    private static String consensus(String server1, String server2, String server3) {
        Map<String, String> votes = new LinkedHashMap<>();
        votes.put("recommender1", server1);
        votes.put("recommender2", server2);
        votes.put("recommender3", server3);
        return RecommenderServer.determineMajority(votes, 2);
    }
}
