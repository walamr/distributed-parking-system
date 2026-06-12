package edu.kinneret.parking.customer.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import edu.kinneret.parking.common.AppConfig;

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
}
