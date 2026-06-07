package edu.kinneret.parking.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AppConfigTest {

    @Test
    void shouldUseCustomerDefaultsForCustomerUiProfile() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of("HMAC_SECRET", "test-secret-1234567890"));

        assertEquals("customer", config.getRabbitMqUsername());
        assertEquals("customer_secure_pass_2026", config.getRabbitMqPassword());
    }

    @Test
    void shouldUsePeoDefaultsForPeoAndSmokeProfiles() {
        AppConfig peoConfig = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.PEO_UI,
                Map.of("HMAC_SECRET", "test-secret-1234567890"));
        AppConfig smokeConfig = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.SMOKE_TEST,
                Map.of("HMAC_SECRET", "test-secret-1234567890"));

        assertEquals("peo_service", peoConfig.getRabbitMqUsername());
        assertEquals("peo_service", smokeConfig.getRabbitMqUsername());
    }

    @Test
    void shouldEnableMongoTlsByDefaultForHostRunApps() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of("HMAC_SECRET", "test-secret-1234567890"));

        assertTrue(config.isMongoTlsEnabled());
        assertEquals("docker/mongodb/certs/ca-cert.pem", config.getMongoTlsCaCertPath());
        assertEquals(false, config.isMongoTlsAllowInvalidHostnames());
    }

    @Test
    void shouldAllowEnvironmentOverrides() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of(
                        "RABBITMQ_USERNAME", "override-user",
                        "RABBITMQ_PASSWORD", "override-pass",
                        "MONGO_TLS_ENABLED", "false",
                        "RABBITMQ_RECOVERY_INTERVAL_MS", "9000",
                        "HMAC_SECRET", "test-secret-1234567890"));

        assertEquals("override-user", config.getRabbitMqUsername());
        assertEquals("override-pass", config.getRabbitMqPassword());
        assertEquals(false, config.isMongoTlsEnabled());
        assertEquals(9000, config.getRabbitMqRecoveryIntervalMs());
    }

    @Test
    void shouldSubstituteMongoCredentialsBasedOnProfile() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.MO_UI,
                Map.of(
                        "MONGO_URI", "mongodb://customer_db_user:db_pass_cust_2026@10.0.201.25:27017,10.0.201.24:27017,10.0.201.23:27017/parking_db?replicaSet=rs0&authSource=admin",
                        "HMAC_SECRET", "test-secret-1234567890"
                ));

        assertEquals("mongodb://mulligan_db_admin:db_pass_admin_99@10.0.201.25:27017,10.0.201.24:27017,10.0.201.23:27017/parking_db?replicaSet=rs0&authSource=admin", config.getMongoUri());
    }

    @Test
    void shouldSubstituteStorageMongoCredentialsBasedOnProfile() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.STORAGE_SERVER,
                Map.of(
                        "MONGO_URI", "mongodb://customer_db_user:db_pass_cust_2026@10.0.201.25:27017/parking_db",
                        "HMAC_SECRET", "test-secret-1234567890"
                ));

        assertEquals("mongodb://peo_db_user:db_pass_peo_2026@10.0.201.25:27017/parking_db", config.getMongoUri());
    }
}
