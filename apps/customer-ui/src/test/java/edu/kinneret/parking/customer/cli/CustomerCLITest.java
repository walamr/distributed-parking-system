package edu.kinneret.parking.customer.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import edu.kinneret.parking.common.AppConfig;
import com.google.gson.JsonObject;

/**
 * Unit tests for the Customer CLI class.
 */
public class CustomerCLITest {

    /**
     * Verifies that the Customer CLI configures itself with the correct 
     * least-privilege customer profile defaults.
     */
    @Test
    public void testCliConfiguration() {
        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.CUSTOMER_UI);
        assertNotNull(config, "Config must not be null");
        assertEquals("customer", config.getRabbitMqUsername(), "RabbitMQ user must be customer");
        assertEquals("transactions.queue", config.getTransactionsQueueName(), "Queue must be transactions.queue");
        assertTrue(config.isRabbitMqTlsEnabled(), "RabbitMQ TLS must be enabled");
    }
    /**
     * Cli payload builder accepts valid parking action.
     */

    @Test
    public void cliPayloadBuilderAcceptsValidParkingAction() {
        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.CUSTOMER_UI);
        String payload = CustomerCLI.buildPayload("604-95-839", "P01", "start", config);

        assertTrue(payload.contains("\"vehicleId\":\"604-95-839\""));
        assertTrue(payload.contains("\"spaceId\":\"P01\""));
        assertTrue(payload.contains("\"type\":\"start\""));
    }
    /**
     * Cli payload builder rejects invalid input.
     */

    @Test
    public void cliPayloadBuilderRejectsInvalidInput() {
        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.CUSTOMER_UI);

        assertThrows(IllegalArgumentException.class,
                () -> CustomerCLI.buildPayload("604-95-839", "DROP", "start", config));
        assertThrows(IllegalArgumentException.class,
                () -> CustomerCLI.buildPayload("604-95-839;DROP", "P01", "start", config));
        assertThrows(IllegalArgumentException.class,
                () -> CustomerCLI.buildPayload("604-95-839", "P01", "delete", config));
    }

    @Test
    public void publishSuccessMessageDoesNotClaimDatabaseSave() {
        String message = CustomerCLI.publishSuccessMessage("start");
        assertTrue(message.contains("accepted by RabbitMQ"));
        assertFalse(message.toLowerCase().contains("saved to mongodb"));
        assertFalse(message.toLowerCase().contains("mongodb"));
        assertFalse(message.toLowerCase().contains("persist"));
    }

    /** Recommendation input accepts trimmed numeric space IDs. */
    @Test
    public void recommendationInputAcceptsValidSpace() {
        assertEquals("3", CustomerCLI.validateRecommendationSpaceId(" 3 "));
    }

    /** Recommendation input rejects empty, nonnumeric, and out-of-range IDs. */
    @Test
    public void recommendationInputRejectsInvalidSpaces() {
        assertThrows(IllegalArgumentException.class, () -> CustomerCLI.validateRecommendationSpaceId(""));
        assertThrows(IllegalArgumentException.class, () -> CustomerCLI.validateRecommendationSpaceId("ABC"));
        assertThrows(IllegalArgumentException.class, () -> CustomerCLI.validateRecommendationSpaceId("0"));
        assertThrows(IllegalArgumentException.class, () -> CustomerCLI.validateRecommendationSpaceId("101"));
    }

    /** Successful consensus is displayed exactly as the assignment result. */
    @Test
    public void successfulRecommendationDisplaysRequestAndResult() {
        JsonObject response = new JsonObject();
        response.addProperty("status", "SUCCESS");
        response.addProperty("result", "Request: Space 3\nResult: Space 2;3, Space 4;3");
        assertEquals("Request: Space 3\nResult: Space 2;3, Space 4;3",
                CustomerCLI.formatRecommendationResponse(response.toString()));
    }

    /** No-majority failure preserves the server's user-facing reason. */
    @Test
    public void noMajorityDisplaysFailure() {
        JsonObject response = new JsonObject();
        response.addProperty("status", "FAILURE");
        response.addProperty("reason", "No majority consensus reached in cluster.");
        assertEquals("RECOMMENDATION FAILURE: No majority consensus reached in cluster.",
                CustomerCLI.formatRecommendationResponse(response.toString()));
    }

    /** Missing recommender response is handled without blocking or a stack trace. */
    @Test
    public void missingResponseDisplaysFriendlyFailure() {
        assertEquals("RECOMMENDATION FAILURE: No response from recommender node.",
                CustomerCLI.formatRecommendationResponse(null));
    }

    /** Malformed recommender response is handled safely. */
    @Test
    public void malformedResponseDisplaysFriendlyFailure() {
        assertEquals("RECOMMENDATION FAILURE: Invalid response from recommender node.",
                CustomerCLI.formatRecommendationResponse("not-json"));
    }
}
