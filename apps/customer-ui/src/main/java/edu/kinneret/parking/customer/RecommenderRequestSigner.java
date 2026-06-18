package edu.kinneret.parking.customer;

import com.google.gson.JsonObject;
import edu.kinneret.parking.common.SecureMessageSigner;
import edu.kinneret.parking.common.ValidationUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds signed customer requests for the recommender protocol.
 */
public final class RecommenderRequestSigner {
    private static final Set<String> SIGNED_FIELDS = Set.of(
            "type", "spaceId", "correlationId", "timestamp", "nonce", "nodeId",
            "localResult", "status", "result", "reason", "vehicleId");

/**

 * Constructs a new RecommenderRequestSigner.

 */

    private RecommenderRequestSigner() {
    }

    /**
     * Creates a signed recommender request JSON object.
     *
     * @param type recommender request type
     * @param spaceId numeric parking space number
     * @param correlationId request correlation identifier
     * @param nodeId sender node identity
     * @param signer HMAC-SHA256 signer
     * @return signed JSON request
     */
    public static JsonObject createSignedRequest(String type, String spaceId, String correlationId,
                                                 String nodeId, SecureMessageSigner signer) {
        return createSignedRequest(type, spaceId, correlationId, nodeId, null, signer);
    }

    /**
     * Creates a signed recommender request JSON object with vehicle ID.
     *
     * @param type recommender request type
     * @param spaceId numeric parking space number
     * @param correlationId request correlation identifier
     * @param nodeId sender node identity
     * @param vehicleId vehicle identification number (vin)
     * @param signer HMAC-SHA256 signer
     * @return signed JSON request
     */
    public static JsonObject createSignedRequest(String type, String spaceId, String correlationId,
                                                 String nodeId, String vehicleId, SecureMessageSigner signer) {
        JsonObject request = new JsonObject();
        request.addProperty("type", ValidationUtils.requireValidMessageType(type, "type"));
        request.addProperty("spaceId", ValidationUtils.requireValidSpaceId(spaceId));
        request.addProperty("correlationId", ValidationUtils.requireValidUuid(correlationId, "correlationId"));
        request.addProperty("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        request.addProperty("nonce", UUID.randomUUID().toString());
        request.addProperty("nodeId", ValidationUtils.requireValidMessageType(nodeId, "nodeId"));
        if (vehicleId != null) {
            request.addProperty("vehicleId", vehicleId);
        }
        request.addProperty("hmac", signer.sign(canonicalSigningContent(request)));
        return request;
    }

/**

 * Canonical signing content.

 * @param message the message

 * @return the string

 */

    private static String canonicalSigningContent(JsonObject message) {
        Map<String, String> fields = new LinkedHashMap<>();
        SIGNED_FIELDS.stream().sorted().forEach(field -> {
            if (message.has(field)) {
                fields.put(field, message.get(field).getAsString());
            }
        });
        StringBuilder canonical = new StringBuilder();
        fields.forEach((key, value) -> canonical.append(key).append('=').append(value).append('\n'));
        return canonical.toString();
    }
}
