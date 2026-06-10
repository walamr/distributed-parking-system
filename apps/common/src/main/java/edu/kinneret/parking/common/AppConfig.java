package edu.kinneret.parking.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads queue-server configuration from environment variables with safe local
 * defaults.
 */
public final class AppConfig {
    private static final String DEFAULT_VHOST = "/parking";
    private static final String DEFAULT_NODES = "localhost:5671,localhost:5673,localhost:5674";
    private static final String DEFAULT_TRANSACTIONS_QUEUE = "transactions.queue";
    private static final String DEFAULT_CITATIONS_QUEUE = "citations.queue";
    private static final boolean DEFAULT_TLS_ENABLED = true;
    private static final String DEFAULT_TRUSTSTORE_PATH = "docker/rabbitmq/certs/truststore.jks";
    private static final String DEFAULT_TRUSTSTORE_PASSWORD = "password";
    private static final String DEFAULT_KEYSTORE_PATH = "docker/rabbitmq/certs/keystore.jks";
    private static final String DEFAULT_KEYSTORE_PASSWORD = "password";
    private static final String DEFAULT_MONGO_URI = "mongodb://%s:%s@mongo1:27017,mongo2:27018,mongo3:27019/parking_db?replicaSet=rs0&authSource=admin";
    private static final boolean DEFAULT_MONGO_TLS_ENABLED = true;
    private static final String DEFAULT_MONGO_TLS_CA_CERT_PATH = "docker/mongodb/certs/ca-cert.pem";
    private static final boolean DEFAULT_MONGO_TLS_ALLOW_INVALID_HOSTNAMES = true;
    private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5000;
    private static final long DEFAULT_RABBITMQ_RECOVERY_INTERVAL_MS = 5000;
    private static final String DEFAULT_HMAC_SECRET = "";
    private static final long DEFAULT_NONCE_TTL_SECONDS = 60;
    private static final double DEFAULT_MAX_ALLOWED_AMOUNT = 10000.0d;

    private final List<ClusterNode> rabbitMqNodes;
    private final String rabbitMqUsername;
    private final String rabbitMqPassword;
    private final String rabbitMqVirtualHost;
    private final boolean rabbitMqTlsEnabled;
    private final String transactionsQueueName;
    private final String citationsQueueName;
    private final String tlsTruststorePath;
    private final String tlsTruststorePassword;
    private final String tlsKeystorePath;
    private final String tlsKeystorePassword;
    private final String tlsServerKeystorePath;
    private final String tlsServerKeystorePassword;
    private final String mongoUri;
    private final int rabbitMqConnectionTimeoutMs;
    private final long rabbitMqRecoveryIntervalMs;
    private final boolean mongoTlsEnabled;
    private final String mongoTlsCaCertPath;
    private final boolean mongoTlsAllowInvalidHostnames;
    private final String hmacSecret;
    private final long nonceTtlSeconds;
    private final double maxAllowedAmount;

    /**
     * Application-specific defaults used to keep least-privilege credentials aligned
     * with each runnable module.
     */
    public enum ApplicationProfile {
        /**
         * Profile for the Customer User Interface application.
         */
        CUSTOMER_UI("customer", "customer_pwd_rotated", "customer_db_user", "db_pwd_rotated_cust"),

        /**
         * Profile for the Parking Enforcement Officer UI application.
         */
        PEO_UI("peo_service", "peo_pwd_rotated", "peo_db_user", "db_pwd_rotated_peo"),

        /**
         * Profile for the Municipality Officer UI application.
         */
        MO_UI("mulligan_admin", "admin_pwd_rotated", "mulligan_db_admin", "db_pwd_rotated_admin"),

        /**
         * Profile for the main message Queue Server backend daemon.
         */
        QUEUE_SERVER("queue_service", "queue_pwd_rotated", "mulligan_db_admin", "db_pwd_rotated_admin"),

        /**
         * Profile for the backend Storage Server microservice.
         */
        STORAGE_SERVER("storage_service", "storage_pwd_rotated", "storage_db_user", "db_pwd_rotated_storage"),

        /**
         * Profile used by integration smoke tests to verify infrastructure sanity.
         */
        SMOKE_TEST("peo_service", "peo_pwd_rotated", "peo_db_user", "db_pwd_rotated_peo");

        private final String defaultUsername;
        private final String defaultPassword;
        private final String defaultMongoUser;
        private final String defaultMongoPass;

        ApplicationProfile(String defaultUsername, String defaultPassword, String defaultMongoUser, String defaultMongoPass) {
            this.defaultUsername = defaultUsername;
            this.defaultPassword = defaultPassword;
            this.defaultMongoUser = defaultMongoUser;
            this.defaultMongoPass = defaultMongoPass;
        }

        /**
         * Returns the default RabbitMQ username for the profile.
         *
         * @return the least-privilege RabbitMQ username
         */
        public String defaultUsername() {
            return defaultUsername;
        }

        /**
         * Returns the default RabbitMQ password for the profile.
         *
         * @return the default RabbitMQ password
         */
        public String defaultPassword() {
            return defaultPassword;
        }

        /**
         * Returns the default MongoDB username for the profile.
         *
         * @return the least-privilege MongoDB username
         */
        public String defaultMongoUser() {
            return defaultMongoUser;
        }

        /**
         * Returns the default MongoDB password for the profile.
         *
         * @return the default MongoDB password
         */
        public String defaultMongoPass() {
            return defaultMongoPass;
        }
    }


    private AppConfig(
            List<ClusterNode> rabbitMqNodes,
            String rabbitMqUsername,
            String rabbitMqPassword,
            String rabbitMqVirtualHost,
            boolean rabbitMqTlsEnabled,
            String transactionsQueueName,
            String citationsQueueName,
            String tlsTruststorePath,
            String tlsTruststorePassword,
            String tlsKeystorePath,
            String tlsKeystorePassword,
            String tlsServerKeystorePath,
            String tlsServerKeystorePassword,
            String mongoUri,
            int rabbitMqConnectionTimeoutMs,
            long rabbitMqRecoveryIntervalMs,
            boolean mongoTlsEnabled,
            String mongoTlsCaCertPath,
            boolean mongoTlsAllowInvalidHostnames,
            String hmacSecret,
            long nonceTtlSeconds,
            double maxAllowedAmount) {
        this.rabbitMqNodes = List.copyOf(rabbitMqNodes);
        this.rabbitMqUsername = rabbitMqUsername;
        this.rabbitMqPassword = rabbitMqPassword;
        this.rabbitMqVirtualHost = rabbitMqVirtualHost;
        this.rabbitMqTlsEnabled = rabbitMqTlsEnabled;
        this.transactionsQueueName = transactionsQueueName;
        this.citationsQueueName = citationsQueueName;
        this.tlsTruststorePath = tlsTruststorePath;
        this.tlsTruststorePassword = tlsTruststorePassword;
        this.tlsKeystorePath = tlsKeystorePath;
        this.tlsKeystorePassword = tlsKeystorePassword;
        this.tlsServerKeystorePath = tlsServerKeystorePath;
        this.tlsServerKeystorePassword = tlsServerKeystorePassword;
        this.mongoUri = mongoUri;
        this.rabbitMqConnectionTimeoutMs = rabbitMqConnectionTimeoutMs;
        this.rabbitMqRecoveryIntervalMs = rabbitMqRecoveryIntervalMs;
        this.mongoTlsEnabled = mongoTlsEnabled;
        this.mongoTlsCaCertPath = mongoTlsCaCertPath;
        this.mongoTlsAllowInvalidHostnames = mongoTlsAllowInvalidHostnames;
        this.hmacSecret = hmacSecret;
        this.nonceTtlSeconds = nonceTtlSeconds;
        this.maxAllowedAmount = maxAllowedAmount;
    }

    private static void loadDotEnv(Map<String, String> env) {
        String[] pathsToTry = { ".env", "../.env", "../../.env" };
        java.io.File envFile = null;
        for (String path : pathsToTry) {
            java.io.File f = new java.io.File(path);
            if (f.exists()) {
                envFile = f;
                break;
            }
        }
        if (envFile != null) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        String key = parts[0].trim();
                        String value = parts[1].trim();
                        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                            value = value.substring(1, value.length() - 1);
                        } else if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                            value = value.substring(1, value.length() - 1);
                        }
                        env.putIfAbsent(key, value);
                    }
                }
            } catch (java.io.IOException e) {
                // Silently ignore configuration loading exceptions
            }
        }
    }

    /**
     * Loads application configuration from the current process environment.
     *
     * @return the resolved application configuration
     */
    public static AppConfig fromEnvironment() {
        Map<String, String> env = new java.util.HashMap<>(System.getenv());
        loadDotEnv(env);
        return fromEnvironment(ApplicationProfile.QUEUE_SERVER, env);
    }

    /**
     * Loads configuration using the defaults for the supplied runnable module.
     *
     * @param profile the application profile whose least-privilege defaults should apply
     * @return the resolved application configuration
     */
    public static AppConfig fromEnvironment(ApplicationProfile profile) {
        Map<String, String> env = new java.util.HashMap<>(System.getenv());
        loadDotEnv(env);
        return fromEnvironment(profile, env);
    }

    /**
     * Loads application configuration from a supplied environment map.
     *
     * @param environment the environment variable map
     * @return the resolved application configuration
     */
    public static AppConfig fromEnvironment(Map<String, String> environment) {
        Map<String, String> env = new java.util.HashMap<>(environment);
        loadDotEnv(env);
        return fromEnvironment(ApplicationProfile.QUEUE_SERVER, env);
    }

    /**
     * Loads application configuration from a supplied environment map using a named
     * application profile.
     *
     * @param profile the application profile whose least-privilege defaults should apply
     * @param environment the environment variable map
     * @return the resolved application configuration
     */
    public static AppConfig fromEnvironment(ApplicationProfile profile, Map<String, String> environment) {
        ApplicationProfile activeProfile = profile == null ? ApplicationProfile.QUEUE_SERVER : profile;
        String username = readOrDefault(environment, "RABBITMQ_USERNAME", activeProfile.defaultUsername());
        String password = readOrDefault(environment, "RABBITMQ_PASSWORD", activeProfile.defaultPassword());
        if ("guest".equals(username)) {
            throw new IllegalArgumentException("RABBITMQ_USERNAME must not use guest credentials.");
        }
        String virtualHost = readOrDefault(environment, "RABBITMQ_VHOST", DEFAULT_VHOST);
        boolean tlsEnabled = Boolean.parseBoolean(readOrDefault(
                environment,
                "RABBITMQ_TLS_ENABLED",
                String.valueOf(DEFAULT_TLS_ENABLED)));
        int connectionTimeoutMs = parsePositiveInt(
                readOrDefault(
                        environment,
                        "RABBITMQ_CONNECTION_TIMEOUT_MS",
                        String.valueOf(DEFAULT_CONNECTION_TIMEOUT_MS)),
                "RABBITMQ_CONNECTION_TIMEOUT_MS");
        long recoveryIntervalMs = parsePositiveLong(
                readOrDefault(
                        environment,
                        "RABBITMQ_RECOVERY_INTERVAL_MS",
                        String.valueOf(DEFAULT_RABBITMQ_RECOVERY_INTERVAL_MS)),
                "RABBITMQ_RECOVERY_INTERVAL_MS");
        String transactionsQueue = ValidationUtils.requireValidQueueName(
                readOrDefault(environment, "RABBITMQ_TRANSACTIONS_QUEUE", DEFAULT_TRANSACTIONS_QUEUE),
                "RABBITMQ_TRANSACTIONS_QUEUE");
        String citationsQueue = ValidationUtils.requireValidQueueName(
                readOrDefault(environment, "RABBITMQ_CITATIONS_QUEUE", DEFAULT_CITATIONS_QUEUE),
                "RABBITMQ_CITATIONS_QUEUE");

        List<ClusterNode> nodes = parseNodes(readOrDefault(environment, "RABBITMQ_NODES", DEFAULT_NODES));
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("At least one RabbitMQ node must be configured.");
        }

        String tlsTruststorePath = readOrDefault(environment, "RABBITMQ_TRUSTSTORE_PATH", DEFAULT_TRUSTSTORE_PATH);
        String tlsTruststorePassword = readOrDefault(environment, "RABBITMQ_TRUSTSTORE_PASSWORD",
                DEFAULT_TRUSTSTORE_PASSWORD);
        String tlsKeystorePath = readOrDefault(environment, "RABBITMQ_KEYSTORE_PATH", DEFAULT_KEYSTORE_PATH);
        String tlsKeystorePassword = readOrDefault(environment, "RABBITMQ_KEYSTORE_PASSWORD", DEFAULT_KEYSTORE_PASSWORD);
        
        String tlsServerKeystorePath = readOrDefault(environment, "TLS_SERVER_KEYSTORE_PATH", tlsKeystorePath);
        String tlsServerKeystorePassword = readOrDefault(environment, "TLS_SERVER_KEYSTORE_PASSWORD", tlsKeystorePassword);

        String mongoUri = environment.get("MONGO_URI");
        if (mongoUri == null || mongoUri.isBlank()) {
            mongoUri = String.format(DEFAULT_MONGO_URI, activeProfile.defaultMongoUser(), activeProfile.defaultMongoPass());
        } else {
            if (mongoUri.startsWith("mongodb://")) {
                int atIndex = mongoUri.indexOf("@");
                if (atIndex > 0) {
                    String prefix = "mongodb://";
                    String rest = mongoUri.substring(atIndex);
                    mongoUri = prefix + activeProfile.defaultMongoUser() + ":" + activeProfile.defaultMongoPass() + rest;
                }
            }
        }
        
        boolean mongoTlsEnabled = Boolean.parseBoolean(readOrDefault(
                environment,
                "MONGO_TLS_ENABLED",
                String.valueOf(DEFAULT_MONGO_TLS_ENABLED)));
        String mongoTlsCaCertPath = readOrDefault(environment, "MONGO_TLS_CA_CERT_PATH", DEFAULT_MONGO_TLS_CA_CERT_PATH);
        boolean mongoTlsAllowInvalidHostnames = Boolean.parseBoolean(readOrDefault(
                environment,
                "MONGO_TLS_ALLOW_INVALID_HOSTNAMES",
                String.valueOf(DEFAULT_MONGO_TLS_ALLOW_INVALID_HOSTNAMES)));
        String hmacSecret = readOrDefault(environment, "HMAC_SECRET", DEFAULT_HMAC_SECRET);
        if (hmacSecret.isBlank()) {
            throw new IllegalStateException("Security Risk: HMAC_SECRET must be configured.");
        }
        if (hmacSecret.equals("change-me-for-real-deployments")) {
            throw new IllegalStateException("Security Risk: You must change the default HMAC_SECRET in production.");
        }
        long nonceTtlSeconds = Long
                .parseLong(readOrDefault(environment, "NONCE_TTL_SECONDS", String.valueOf(DEFAULT_NONCE_TTL_SECONDS)));
        double maxAllowedAmount = Double.parseDouble(readOrDefault(environment, "MAX_ALLOWED_AMOUNT", String.valueOf(DEFAULT_MAX_ALLOWED_AMOUNT)));

        return new AppConfig(
                nodes,
                username,
                password,
                virtualHost,
                tlsEnabled,
                transactionsQueue,
                citationsQueue,
                tlsTruststorePath,
                tlsTruststorePassword,
                tlsKeystorePath,
                tlsKeystorePassword,
                tlsServerKeystorePath,
                tlsServerKeystorePassword,
                mongoUri,
                connectionTimeoutMs,
                recoveryIntervalMs,
                mongoTlsEnabled,
                mongoTlsCaCertPath,
                mongoTlsAllowInvalidHostnames,
                hmacSecret,
                nonceTtlSeconds,
                maxAllowedAmount);

    }

    /**
     * Returns the configured RabbitMQ nodes.
     *
     * @return the cluster node list
     */
    public List<ClusterNode> getRabbitMqNodes() {
        return rabbitMqNodes;
    }

    /**
     * Returns the RabbitMQ application username.
     *
     * @return the configured username
     */
    public String getRabbitMqUsername() {
        return rabbitMqUsername;
    }

    /**
     * Returns the RabbitMQ application password.
     *
     * @return the configured password
     */
    public String getRabbitMqPassword() {
        return rabbitMqPassword;
    }

    /**
     * Returns the RabbitMQ virtual host.
     *
     * @return the configured virtual host
     */
    public String getRabbitMqVirtualHost() {
        return rabbitMqVirtualHost;
    }

    /**
     * Indicates whether TLS is requested for RabbitMQ connections.
     *
     * @return {@code true} when TLS should be enabled
     */
    public boolean isRabbitMqTlsEnabled() {
        return rabbitMqTlsEnabled;
    }

    /**
     * Indicates whether TLS is requested for RabbitMQ connections.
     *
     * @return {@code true} when TLS should be enabled
     */
    public boolean isRabbitMqTlsAllowInvalidHostnames() {
        return Boolean.parseBoolean(System.getenv().getOrDefault("RABBITMQ_TLS_ALLOW_INVALID_HOSTNAMES", "true"));
    }

    /**
     * Returns the transactions queue name.
     *
     * @return the configured transactions queue
     */
    public String getTransactionsQueueName() {
        return transactionsQueueName;
    }

    /**
     * Returns the citations queue name.
     *
     * @return the configured citations queue
     */
    public String getCitationsQueueName() {
        return citationsQueueName;
    }

    /**
     * Returns the RabbitMQ connection timeout in milliseconds.
     *
     * @return the configured timeout
     */
    public int getRabbitMqConnectionTimeoutMs() {
        return rabbitMqConnectionTimeoutMs;
    }

    /**
     * Returns the RabbitMQ automatic recovery interval in milliseconds.
     *
     * @return the recovery interval
     */
    public long getRabbitMqRecoveryIntervalMs() {
        return rabbitMqRecoveryIntervalMs;
    }

    /**
     * Returns the path to the TLS truststore (JKS).
     *
     * @return the truststore file path
     */
    public String getTlsTruststorePath() {
        return tlsTruststorePath;
    }

    /**
     * Returns the password for the TLS truststore.
     *
     * @return the truststore password
     */
    public String getTlsTruststorePassword() {
        return tlsTruststorePassword;
    }

    /**
     * Returns the path to the TLS keystore (JKS) containing the client certificate.
     *
     * @return the keystore file path
     */
    public String getTlsKeystorePath() {
        return tlsKeystorePath;
    }

    /**
     * Returns the password for the TLS keystore.
     *
     * @return the keystore password
     */
    public String getTlsKeystorePassword() {
        return tlsKeystorePassword;
    }

    /**
     * @return the server keystore file path
     */
    public String getTlsServerKeystorePath() {
        return tlsServerKeystorePath;
    }

    /**
     * @return the password for the server keystore
     */
    public String getTlsServerKeystorePassword() {
        return tlsServerKeystorePassword;
    }

    /**
     * Returns the full MongoDB connection URI including cluster nodes and replica
     * set name.
     *
     * @return the MongoDB connection string
     */
    public String getMongoUri() {
        return mongoUri;
    }

    /**
     * Indicates whether TLS is enabled for MongoDB connections.
     *
     * @return {@code true} when MongoDB TLS is enabled
     */
    public boolean isMongoTlsEnabled() {
        return mongoTlsEnabled;
    }

    /**
     * Returns the PEM CA certificate used to trust the MongoDB replica-set TLS
     * certificates.
     *
     * @return the MongoDB CA certificate path
     */
    public String getMongoTlsCaCertPath() {
        return mongoTlsCaCertPath;
    }

    /**
     * Indicates whether MongoDB hostname validation should be relaxed for local
     * host-to-container development.
     *
     * @return {@code true} when invalid hostnames may be accepted
     */
    public boolean isMongoTlsAllowInvalidHostnames() {
        return mongoTlsAllowInvalidHostnames;
    }

    /**
     * Returns the shared secret key used for HMAC-SHA256 message signing.
     *
     * @return the HMAC secret
     */
    public String getHmacSecret() {
        return hmacSecret;
    }

    /**
     * Returns the Time-To-Live for message nonces in seconds.
     *
     * @return the nonce TTL
     */
    public long getNonceTtlSeconds() {
        return nonceTtlSeconds;
    }

    /**
     * Returns the maximum allowed amount for transactions.
     *
     * @return the max allowed amount
     */
    public double getMaxAllowedAmount() {
        return maxAllowedAmount;
    }

    /**
     * Returns a redacted summary suitable for startup logging.
     *
     * @return a redacted configuration summary
     */
    public String toRedactedSummary() {
        return "AppConfig{"
                + "rabbitMqNodes=" + rabbitMqNodes
                + ", rabbitMqUsername='" + rabbitMqUsername + '\''
                + ", rabbitMqPassword='***'"
                + ", rabbitMqVirtualHost='" + rabbitMqVirtualHost + '\''
                + ", rabbitMqTlsEnabled=" + rabbitMqTlsEnabled
                + ", transactionsQueueName='" + transactionsQueueName + '\''
                + ", citationsQueueName='" + citationsQueueName + '\''
                + ", rabbitMqConnectionTimeoutMs=" + rabbitMqConnectionTimeoutMs
                + ", rabbitMqRecoveryIntervalMs=" + rabbitMqRecoveryIntervalMs
                + ", mongoUri='" + SecurityLogger.sanitize(mongoUri) + '\''
                + ", mongoTlsEnabled=" + mongoTlsEnabled
                + '}';
    }

    private static String readOrDefault(Map<String, String> environment, String key, String defaultValue) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static int parsePositiveInt(String rawValue, String fieldName) {
        try {
            return ValidationUtils.requirePositive(Integer.parseInt(rawValue), fieldName);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(fieldName + " must be a positive integer.", ex);
        }
    }

    private static long parsePositiveLong(String rawValue, String fieldName) {
        try {
            long parsedValue = Long.parseLong(rawValue);
            if (parsedValue <= 0) {
                throw new IllegalArgumentException(fieldName + " must be a positive integer.");
            }
            return parsedValue;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(fieldName + " must be a positive integer.", ex);
        }
    }

    private static List<ClusterNode> parseNodes(String rawNodes) {
        List<ClusterNode> nodes = new ArrayList<>();
        String[] parts = rawNodes.split(",");
        int counter = 1;
        for (String part : parts) {
            String trimmedPart = part.trim();
            if (trimmedPart.isEmpty()) {
                continue;
            }
            String[] addressParts = trimmedPart.split(":");
            if (addressParts.length != 2) {
                throw new IllegalArgumentException("Invalid RabbitMQ node entry: " + trimmedPart);
            }
            String host = ValidationUtils.requireNonEmpty(addressParts[0], "rabbitMqNodeHost");
            int port = parsePositiveInt(addressParts[1], "rabbitMqNodePort");
            nodes.add(new ClusterNode(host, port, "rabbitmq" + counter, true));
            counter++;
        }
        return nodes;
    }
}
