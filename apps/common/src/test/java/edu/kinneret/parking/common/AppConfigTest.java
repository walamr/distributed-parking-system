package edu.kinneret.parking.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**

 * Represents a class AppConfigTest.

 */

class AppConfigTest {
    /**
     * Should use hardcoded defaults when passwords are missing.
     */

    @Test
    void shouldUseHardcodedDefaultsWhenPasswordsAreMissing() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of("HMAC_SECRET", "test-secret-1234567890"));
        assertEquals("customer", config.getRabbitMqUsername());
        assertEquals("customer_pwd_rotated", config.getRabbitMqPassword());
    }
    /**
     * Should use customer defaults with configured passwords.
     */

    @Test
    void shouldUseCustomerDefaultsWithConfiguredPasswords() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of(
                        "HMAC_SECRET", "test-secret-1234567890",
                        "RABBITMQ_PASSWORD", "custom-mq-pass",
                        "MONGO_PASSWORD", "custom-mongo-pass"
                ));

        assertEquals("customer", config.getRabbitMqUsername());
        assertEquals("custom-mq-pass", config.getRabbitMqPassword());
    }
    /**
     * Should use peo defaults for peo and smoke profiles.
     */

    @Test
    void shouldUsePeoDefaultsForPeoAndSmokeProfiles() {
        AppConfig peoConfig = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.PEO_UI,
                Map.of(
                        "HMAC_SECRET", "test-secret-1234567890",
                        "RABBITMQ_PASSWORD", "peo-pass",
                        "MONGO_PASSWORD", "peo-db-pass"
                ));
        AppConfig smokeConfig = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.SMOKE_TEST,
                Map.of(
                        "HMAC_SECRET", "test-secret-1234567890",
                        "RABBITMQ_PASSWORD", "smoke-pass",
                        "MONGO_PASSWORD", "smoke-db-pass"
                ));

        assertEquals("peo_service", peoConfig.getRabbitMqUsername());
        assertEquals("peo_service", smokeConfig.getRabbitMqUsername());
    }
    /**
     * Should enable mongo tls by default for host run apps.
     */

    @Test
    void shouldEnableMongoTlsByDefaultForHostRunApps() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of(
                        "HMAC_SECRET", "test-secret-1234567890",
                        "RABBITMQ_PASSWORD", "pass",
                        "MONGO_PASSWORD", "pass"
                ));

        assertTrue(config.isMongoTlsEnabled());
        assertEquals("docker/mongodb/certs/ca-cert.pem", config.getMongoTlsCaCertPath());
        assertEquals(false, config.isMongoTlsAllowInvalidHostnames());
    }
    /**
     * Should allow environment overrides.
     */

    @Test
    void shouldAllowEnvironmentOverrides() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of(
                        "RABBITMQ_USERNAME", "override-user",
                        "RABBITMQ_PASSWORD", "override-pass",
                        "MONGO_PASSWORD", "override-mongo-pass",
                        "MONGO_TLS_ENABLED", "false",
                        "RABBITMQ_RECOVERY_INTERVAL_MS", "9000",
                        "HMAC_SECRET", "test-secret-1234567890"));

        assertEquals("override-user", config.getRabbitMqUsername());
        assertEquals("override-pass", config.getRabbitMqPassword());
        assertEquals(false, config.isMongoTlsEnabled());
        assertEquals(9000, config.getRabbitMqRecoveryIntervalMs());
    }
    /**
     * Should substitute mongo credentials based on profile.
     */

    @Test
    void shouldSubstituteMongoCredentialsBasedOnProfile() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.MO_UI,
                Map.of(
                        "RABBITMQ_PASSWORD", "pass",
                        "MONGO_PASSWORD", "db_pwd_rotated_admin",
                        "MONGO_URI", "mongodb://customer_db_user:db_pwd_rotated_cust@10.0.201.25:27017,10.0.201.24:27017,10.0.201.23:27017/parking_db?replicaSet=rs0&authSource=admin",
                        "HMAC_SECRET", "test-secret-1234567890"
                ));

        assertEquals("mongodb://mulligan_db_admin:db_pwd_rotated_admin@10.0.201.25:27017,10.0.201.24:27017,10.0.201.23:27017/parking_db?replicaSet=rs0&authSource=admin&retryWrites=true&w=majority", config.getMongoUri());
    }
    /**
     * Should substitute storage mongo credentials based on profile.
     */

    @Test
    void shouldSubstituteStorageMongoCredentialsBasedOnProfile() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.STORAGE_SERVER,
                Map.of(
                        "RABBITMQ_PASSWORD", "pass",
                        "MONGO_PASSWORD", "db_pwd_rotated_storage",
                        "MONGO_URI", "mongodb://customer_db_user:db_pwd_rotated_cust@10.0.201.25:27017/parking_db",
                        "HMAC_SECRET", "test-secret-1234567890"
                ));

        assertEquals("mongodb://storage_db_user:db_pwd_rotated_storage@10.0.201.25:27017/parking_db?replicaSet=rs0&authSource=admin&retryWrites=true&w=majority", config.getMongoUri());
    }

    @Test
    void shouldExpandSingleHostMongoUriToConfiguredReplicaSetHosts() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.STORAGE_SERVER,
                Map.of(
                        "RABBITMQ_PASSWORD", "pass",
                        "MONGO_PASSWORD", "storage-pass",
                        "MONGO_URI", "mongodb://old_user:old_pass@10.0.201.23:27017/parking_db?directConnection=true",
                        "MONGO1_IP", "10.0.201.23",
                        "MONGO2_IP", "10.0.201.24",
                        "MONGO3_IP", "10.0.201.25",
                        "HMAC_SECRET", "test-secret-1234567890"
                ));

        String uri = config.getMongoUri();
        assertTrue(uri.contains("10.0.201.23:27017,10.0.201.24:27017,10.0.201.25:27017"));
        assertTrue(uri.contains("replicaSet=rs0"));
        assertTrue(uri.contains("authSource=admin"));
        assertTrue(uri.contains("retryWrites=true"));
        assertTrue(uri.contains("w=majority"));
        assertFalse(uri.contains("directConnection=true"));
        assertTrue(uri.startsWith("mongodb://storage_db_user:storage-pass@"));
    }

    @Test
    void shouldKeepRuntimeMongoIpsAboveStaleCheckedInEnvFiles() {
        AppConfig config = AppConfig.fromEnvironment(Map.of(
                "RABBITMQ_PASSWORD", "runtime-rabbit-pass",
                "MONGO_PASSWORD", "runtime-mongo-pass",
                "MONGO_URI", "mongodb://runtime_user:runtime_pass@192.168.70.10:27017/parking_db?directConnection=true",
                "MONGO1_IP", "192.168.70.10",
                "MONGO2_IP", "192.168.70.11",
                "MONGO3_IP", "192.168.70.12",
                "HMAC_SECRET", "test-secret-1234567890"
        ));

        String uri = config.getMongoUri();
        assertTrue(uri.contains("192.168.70.10:27017,192.168.70.11:27017,192.168.70.12:27017"));
        assertFalse(uri.contains("10.0.201.23"));
        assertFalse(uri.contains("directConnection=true"));
    }

    @Test
    void shouldExposeFailoverSafeMongoTimeoutDefaults() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.MO_UI,
                Map.of(
                        "RABBITMQ_PASSWORD", "pass",
                        "MONGO_PASSWORD", "pass",
                        "HMAC_SECRET", "test-secret-1234567890"
                ));

        assertEquals(30000, config.getMongoServerSelectionTimeoutMs());
        assertEquals(10000, config.getMongoConnectTimeoutMs());
        assertEquals(10000, config.getMongoSocketTimeoutMs());
    }

    @Test
    void shouldSanitizeMongoPasswordsInLogs() {
        String sanitized = SecurityLogger.sanitize(
                "uri=mongodb://storage_db_user:secret-pass@10.0.201.23:27017/parking_db?replicaSet=rs0");

        assertFalse(sanitized.contains("storage_db_user:secret-pass"));
        assertTrue(sanitized.contains("mongodb://<redacted>:<redacted>@10.0.201.23:27017"));
    }

    @Test
    void shouldKeepRuntimeRabbitNodesAboveStaleCheckedInEnvFiles() {
        AppConfig config = AppConfig.fromEnvironment(
                Map.of(
                        "RABBITMQ_NODES", "192.168.88.10:5671,192.168.88.11:5671,192.168.88.12:5671",
                        "RABBITMQ_PASSWORD", "runtime-rabbit-pass",
                        "MONGO_PASSWORD", "runtime-mongo-pass",
                        "HMAC_SECRET", "test-secret-1234567890"
                ));

        assertEquals("192.168.88.10", config.getRabbitMqNodes().get(0).getHost());
        assertEquals("192.168.88.11", config.getRabbitMqNodes().get(1).getHost());
        assertEquals("192.168.88.12", config.getRabbitMqNodes().get(2).getHost());
    }

    @Test
    void shouldDefaultExpectedNodesToConfiguredNodeCountAndConfirmTimeout() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of(
                        "RABBITMQ_NODES", "10.0.201.16:5671,10.0.201.17:5671,10.0.201.18:5671",
                        "RABBITMQ_PASSWORD", "pass",
                        "MONGO_PASSWORD", "pass",
                        "HMAC_SECRET", "test-secret-1234567890"));

        assertEquals(10000, config.getRabbitMqPublishConfirmTimeoutMs());
        assertEquals(3, config.getRabbitMqExpectedNodes());
    }

    @Test
    void shouldAllowOverridingPublisherConfirmTimeoutAndExpectedNodes() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of(
                        "RABBITMQ_NODES", "10.0.201.16:5671,10.0.201.17:5671,10.0.201.18:5671",
                        "RABBITMQ_PUBLISH_CONFIRM_TIMEOUT_MS", "15000",
                        "RABBITMQ_EXPECTED_NODES", "2",
                        "RABBITMQ_PASSWORD", "pass",
                        "MONGO_PASSWORD", "pass",
                        "HMAC_SECRET", "test-secret-1234567890"));

        assertEquals(15000, config.getRabbitMqPublishConfirmTimeoutMs());
        assertEquals(2, config.getRabbitMqExpectedNodes());
    }

    @Test
    void shouldExposeRabbitMqDiagnosticsWithoutSecrets() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of(
                        "RABBITMQ_NODES", "10.0.201.16:5671,10.0.201.17:5671,10.0.201.18:5671",
                        "RABBITMQ_PASSWORD", "super-secret-rabbit",
                        "MONGO_PASSWORD", "mongo-pass",
                        "HMAC_SECRET", "test-secret-1234567890"
                ));

        String diagnostics = config.toRedactedSummary()
                + ", publisherConfirmsEnabled=true, consumerManualAckEnabled=false";
        assertTrue(diagnostics.contains("10.0.201.16"));
        assertTrue(diagnostics.contains("rabbitMqVirtualHost='/parking'"));
        assertTrue(diagnostics.contains("transactions.queue"));
        assertTrue(diagnostics.contains("citations.queue"));
        assertTrue(diagnostics.contains("publisherConfirmsEnabled=true"));
        assertFalse(diagnostics.contains("super-secret-rabbit"));
        assertFalse(diagnostics.contains("mongo-pass"));
    }
}
