package edu.kinneret.parking.recommender;

import java.io.Serializable;

/**
 * Immutable value object representing a single recommended parking space together with
 * the number of citations recorded against it. Implements {@link Serializable} so it can
 * be exchanged between recommender nodes.
 *
 * @param spaceId       the identifier of the recommended parking space
 * @param citationCount the number of citations associated with the space
 */
public record RecommendationResult(String spaceId, long citationCount) implements Serializable {
    /**
     * Serializes the recommendation result to a standard peer-communication format (e.g. "spaceId;citationCount").
     *
     * @return serialized recommendation result string
     */
    @Override
    public String toString() {
        return spaceId + ";" + citationCount;
    }
}
