package edu.kinneret.parking.peo.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import edu.kinneret.parking.common.AppConfig;

/**
 * Unit tests for the PEO CLI class.
 */
public class PEOCLITest {

    /**
     * Verifies that the PEO CLI configures itself with the correct 
     * least-privilege PEO service defaults.
     */
    @Test
    public void testCliConfiguration() {
        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.PEO_UI);
        assertNotNull(config, "Config must not be null");
        assertEquals("peo_service", config.getRabbitMqUsername(), "RabbitMQ user must be peo_service");
        assertEquals("citations.queue", config.getCitationsQueueName(), "Queue must be citations.queue");
        assertTrue(config.isRabbitMqTlsEnabled(), "RabbitMQ TLS must be enabled");
    }

    @Test
    public void cliPayloadBuilderAcceptsValidCitation() {
        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.PEO_UI);
        String payload = PEOCLI.buildCitationPayload("604-95-839", "P01", "150", "Expired meter", config);

        assertTrue(payload.contains("\"vehicleId\":\"604-95-839\""));
        assertTrue(payload.contains("\"spaceId\":\"P01\""));
        assertTrue(payload.contains("\"amount\":150.0"));
        assertTrue(payload.contains("\"reason\":\"Expired meter\""));
    }

    @Test
    public void cliPayloadBuilderRejectsInvalidCitationInput() {
        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.PEO_UI);

        assertThrows(IllegalArgumentException.class,
                () -> PEOCLI.buildCitationPayload("604-95-839", "P01", "-1", "Expired meter", config));
        assertThrows(IllegalArgumentException.class,
                () -> PEOCLI.buildCitationPayload("604-95-839", "DROP", "150", "Expired meter", config));
        assertThrows(IllegalArgumentException.class,
                () -> PEOCLI.buildCitationPayload("604-95-839", "P01", "150", "<script>", config));
    }
}
