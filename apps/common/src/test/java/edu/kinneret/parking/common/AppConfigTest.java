package edu.kinneret.parking.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AppConfigTest {

    @Test
    void shouldUseHardcodedDefaultsWhenPasswordsAreMissing() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of("HMAC_SECRET", "test-secret-1234567890"));
        assertEquals("customer", config.getRabbitMqUsername());
        assertEquals("customer_pwd_rotated", config.getRabbitMqPassword());
    }

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
        assertEquals(true, config.isMongoTlsAllowInvalidHostnames());
    }

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

        assertEquals("mongodb://mulligan_db_admin:db_pwd_rotated_admin@10.0.201.25:27017,10.0.201.24:27017,10.0.201.23:27017/parking_db?replicaSet=rs0&authSource=admin", config.getMongoUri());
    }

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

        assertEquals("mongodb://storage_db_user:db_pwd_rotated_storage@10.0.201.25:27017/parking_db", config.getMongoUri());
    }
}
