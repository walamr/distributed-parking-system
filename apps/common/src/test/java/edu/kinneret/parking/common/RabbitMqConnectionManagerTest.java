package edu.kinneret.parking.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rabbitmq.client.ConnectionFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RabbitMqConnectionManagerTest {

    @Test
    void shouldConfigureFactoryForRecoveryAndTls() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.PEO_UI,
                Map.of(
                        "RABBITMQ_NODES", "localhost:5671,localhost:5673,localhost:5674",
                        "RABBITMQ_TLS_ENABLED", "false",
                        "RABBITMQ_RECOVERY_INTERVAL_MS", "7000",
                        "HMAC_SECRET", "test-secret-1234567890"));
        RabbitMqConnectionManager connectionManager = new RabbitMqConnectionManager(config);

        ConnectionFactory factory = connectionManager.buildFactory(config.getRabbitMqNodes().getFirst());

        assertEquals("localhost", factory.getHost());
        assertEquals(5671, factory.getPort());
        assertEquals("/parking", factory.getVirtualHost());
        assertTrue(factory.isAutomaticRecoveryEnabled());
        assertTrue(factory.isTopologyRecoveryEnabled());
        assertEquals(7000, factory.getNetworkRecoveryInterval());
    }
}
