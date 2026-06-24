package edu.kinneret.parking.recommender;

import java.io.Serializable;

/**
 * Represents a single recommended parking space with its citation count.
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
