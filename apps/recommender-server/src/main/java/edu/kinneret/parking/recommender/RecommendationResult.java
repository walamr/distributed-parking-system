package edu.kinneret.parking.recommender;

import java.io.Serializable;

/**
 * Represents a single recommended parking space with its citation count.
 */
public record RecommendationResult(String spaceId, long citationCount) implements Serializable {
    @Override
    public String toString() {
        return spaceId + ";" + citationCount;
    }
}
