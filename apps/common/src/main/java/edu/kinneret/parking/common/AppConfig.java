package edu.kinneret.parking.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads queue-server configuration from environment variables with safe local
 * defaults.
 */
public final class AppConfig {
    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(AppConfig.class.getName());
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
    private static final boolean DEFAULT_MONGO_TLS_ALLOW_INVALID_HOSTNAMES = false;
    private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5000;
    private static final long DEFAULT_RABBITMQ_RECOVERY_INTERVAL_MS = 5000;
    private static final String DEFAULT_HMAC_SECRET = "";
    private static final long DEFAULT_NONCE_TTL_SECONDS = 1200;
    private static final double DEFAULT_MAX_ALLOWED_AMOUNT = 10000.0d;

    private final List<ClusterNode> rabbitMqNodes;
    private final String rabbitMqUsername;
    private final String rabbitMqPassword;
    private final String rabbitMqVirtualHost;
    private final boolean rabbitMqTlsEnabled;
    private final boolean rabbitMqTlsAllowInvalidHostnames;
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
    private final List<ClusterNode> recommenderNodes;
    private final String mongo1Ip;
    private final String mongo2Ip;
    private final String mongo3Ip;

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

    /**
     * Constructs a fully-configured AppConfig with all infrastructure parameters.
     *
     * @param rabbitMqNodes                   the RabbitMQ cluster nodes
     * @param rabbitMqUsername                the RabbitMQ username
     * @param rabbitMqPassword                the RabbitMQ password
     * @param rabbitMqVirtualHost             the RabbitMQ virtual host
     * @param rabbitMqTlsEnabled              whether TLS is enabled for RabbitMQ
     * @param rabbitMqTlsAllowInvalidHostnames whether to skip hostname verification for RabbitMQ TLS
     * @param transactionsQueueName           the transactions queue name
     * @param citationsQueueName              the citations queue name
     * @param tlsTruststorePath               the TLS truststore path
     * @param tlsTruststorePassword           the TLS truststore password
     * @param tlsKeystorePath                 the TLS client keystore path
     * @param tlsKeystorePassword             the TLS client keystore password
     * @param tlsServerKeystorePath           the TLS server keystore path
     * @param tlsServerKeystorePassword       the TLS server keystore password
     * @param mongoUri                        the full MongoDB connection URI
     * @param rabbitMqConnectionTimeoutMs     the RabbitMQ connection timeout in milliseconds
     * @param rabbitMqRecoveryIntervalMs      the RabbitMQ automatic recovery interval in milliseconds
     * @param mongoTlsEnabled                 whether TLS is enabled for MongoDB
     * @param mongoTlsCaCertPath              the MongoDB TLS CA certificate path
     * @param mongoTlsAllowInvalidHostnames   whether to skip hostname verification for MongoDB TLS
     * @param hmacSecret                      the HMAC-SHA256 signing secret
     * @param nonceTtlSeconds                 the nonce time-to-live in seconds
     * @param maxAllowedAmount                the maximum allowed citation or transaction amount
     * @param recommenderNodes                the recommender cluster nodes
     */

    private AppConfig(
            List<ClusterNode> rabbitMqNodes,
            String rabbitMqUsername,
            String rabbitMqPassword,
            String rabbitMqVirtualHost,
            boolean rabbitMqTlsEnabled,
            boolean rabbitMqTlsAllowInvalidHostnames,
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
            double maxAllowedAmount,
            List<ClusterNode> recommenderNodes,
            String mongo1Ip,
            String mongo2Ip,
            String mongo3Ip) {
        this.rabbitMqNodes = List.copyOf(rabbitMqNodes);
        this.rabbitMqUsername = rabbitMqUsername;
        this.rabbitMqPassword = rabbitMqPassword;
        this.rabbitMqVirtualHost = rabbitMqVirtualHost;
        this.rabbitMqTlsEnabled = rabbitMqTlsEnabled;
        this.rabbitMqTlsAllowInvalidHostnames = rabbitMqTlsAllowInvalidHostnames;
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
        this.recommenderNodes = List.copyOf(recommenderNodes);
        this.mongo1Ip = mongo1Ip;
        this.mongo2Ip = mongo2Ip;
        this.mongo3Ip = mongo3Ip;
    }

    /**
     * Loads key-value pairs from a {@code .env} file into the supplied map.
     * Searches for the file in the working directory and two parent directories.
     *
     * @param env the mutable map to populate with values found in the file
     */
    private static void loadEnvFile(String path, Map<String, String> env) {
        java.io.File file = new java.io.File(path);
        if (file.exists()) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("\uFEFF")) {
                        line = line.substring(1);
                    }
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
                        env.put(key, value);
                    }
                }
            } catch (java.io.IOException e) {
                // Silently ignore configuration loading exceptions
            }
        }
    }

    private static void overrideWithNetworkIps(Map<String, String> env) {
        String mongo1 = env.get("MONGO1_IP");
        String mongo2 = env.get("MONGO2_IP");
        String mongo3 = env.get("MONGO3_IP");
        String rabbit1 = env.get("RABBIT1_IP");
        String rabbit2 = env.get("RABBIT2_IP");
        String rabbit3 = env.get("RABBIT3_IP");
        String recommender1 = env.get("RECOMMENDER1_IP");
        String recommender2 = env.get("RECOMMENDER2_IP");
        String recommender3 = env.get("RECOMMENDER3_IP");

        // Keep configuration from network-ips.env exactly as defined
        if (mongo1 != null && mongo2 != null && mongo3 != null &&
            rabbit1 != null && rabbit2 != null && rabbit3 != null &&
            recommender1 != null && recommender2 != null && recommender3 != null) {
            
            String os = System.getProperty("os.name", "").toLowerCase();
            boolean isWindowsOrMac = os.contains("win") || os.contains("mac");
            boolean isLocal = mongo1.equals(mongo2) || mongo1.equals("127.0.0.1") || mongo1.equals("localhost");
            
            System.out.println("[AppConfig] mongo1=" + mongo1 + ", mongo2=" + mongo2 + ", isLocal=" + isLocal + ", isWindowsOrMac=" + isWindowsOrMac);
            
            String r1p, r2p, r3p;
            String m1p, m2p, m3p;
            
            if (isWindowsOrMac && isLocal) {
                // On Windows/Mac, Docker Desktop exposes ports on localhost.
                // We must tell TLS to allow invalid hostnames since we connect via localhost
                // but the certificates only contain the internal 10.x IPs or container names.
                env.put("RABBITMQ_TLS_ALLOW_INVALID_HOSTNAMES", "true");
                env.put("MONGO_TLS_ALLOW_INVALID_HOSTNAMES", "true");
                
                // RabbitMQ and Recommender don't have custom DNS resolvers in our code,
                // so we MUST connect to them directly via 127.0.0.1 and their exposed ports.
                rabbit1 = "127.0.0.1"; rabbit2 = "127.0.0.1"; rabbit3 = "127.0.0.1";
                recommender1 = "127.0.0.1"; recommender2 = "127.0.0.1"; recommender3 = "127.0.0.1";
                r1p = "5671"; r2p = "5673"; r3p = "5674";
                m1p = "27017"; m2p = "27018"; m3p = "27019";
                
                // MongoDB requires us to connect using the exact hostnames (mongo1, mongo2, mongo3)
                // for its replica set validation to pass. However, our MongoConnectionManager has a custom 
                // InetAddressResolver that intercepts 'mongo1' and routes it to the IP defined in MONGO1_IP.
                // So we override MONGO_IPs to localhost!
                env.put("MONGO1_IP", "127.0.0.1");
                env.put("MONGO2_IP", "127.0.0.1");
                env.put("MONGO3_IP", "127.0.0.1");
            } else if (isPrivateDockerSubnet(mongo1)) {
                // When connecting to a remote Docker network (e.g. 10.0.x.x lab machines),
                // TLS certificates are issued for internal container names/IPs, not for the external IPs.
                // We must allow invalid hostnames so the TLS handshake succeeds.
                env.put("RABBITMQ_TLS_ALLOW_INVALID_HOSTNAMES", "true");
                env.put("MONGO_TLS_ALLOW_INVALID_HOSTNAMES", "true");
                r1p = "5671"; r2p = "5671"; r3p = "5671";
                m1p = "27017"; m2p = "27017"; m3p = "27017";
                env.put("MONGO1_IP", mongo1);
                env.put("MONGO2_IP", mongo2);
                env.put("MONGO3_IP", mongo3);
            } else {
                r1p = "5671"; r2p = "5671"; r3p = "5671";
                m1p = "27017"; m2p = "27017"; m3p = "27017";
                env.put("MONGO1_IP", mongo1);
                env.put("MONGO2_IP", mongo2);
                env.put("MONGO3_IP", mongo3);
            }

            env.put("RABBITMQ_NODES", rabbit1 + ":" + r1p + "," + rabbit2 + ":" + r2p + "," + rabbit3 + ":" + r3p);
            // CRITICAL: Notice that MONGO_URI always uses the original container names (mongo1, mongo2, mongo3)
            // even if we are on Windows! This is because MongoDB strictly validates hostnames in the replica set config.
            // The custom InetAddressResolver in MongoConnectionManager uses MONGO1_IP to perform the actual routing.
            env.put("MONGO_URI", "mongodb://dummy:dummy@mongo1:" + m1p + ",mongo2:" + m2p + ",mongo3:" + m3p + "/parking_db?replicaSet=rs0&authSource=admin");
            env.put("RECOMMENDER_NODES", recommender1 + ":8091," + recommender2 + ":8092," + recommender3 + ":8093");
        }
    }

    /**
     * Returns true if the IP address is in a private Docker-assigned subnet
     * (typically 10.0.x.x, 172.17-31.x.x, or 192.168.x.x).
     */
    private static boolean isPrivateDockerSubnet(String ip) {
        if (ip == null) return false;
        return ip.startsWith("10.") || ip.startsWith("192.168.") ||
               (ip.startsWith("172.") && isInRange172(ip));
    }

    private static boolean isInRange172(String ip) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length < 2) return false;
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Loads key-value pairs from a {@code .env} file into the supplied map.
     * Searches for the file in the working directory and two parent directories.
     *
     * @param env the mutable map to populate with values found in the file
     */
    private static void loadDotEnv(Map<String, String> env) {
        // First load .env
        String[] pathsToTry = { ".env", "../.env", "../../.env" };
        for (String path : pathsToTry) {
            loadEnvFile(path, env);
        }

        // Then load network-ips.env to override values
        String[] configPaths = { "network-ips.env", "../network-ips.env", "../../network-ips.env" };
        for (String path : configPaths) {
            loadEnvFile(path, env);
        }

        // Dynamically override variables using loaded network IPs
        overrideWithNetworkIps(env);
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
        
        // Dynamically load profile-specific configs for unified local testing
        if (profile != null) {
            // Always clear the username/password loaded from the shared .env so that
            // readOrDefault below will fall back to the correct per-profile credentials.
            // The profile-specific env file (if found) can still override these.
            env.remove("RABBITMQ_USERNAME");
            env.remove("RABBITMQ_PASSWORD");

            String profileEnvFile = null;
            switch (profile) {
                case CUSTOMER_UI: profileEnvFile = "env-configs/customer.env"; break;
                case PEO_UI: profileEnvFile = "env-configs/peo.env"; break;
                case MO_UI: profileEnvFile = "env-configs/mo.env"; break;
                case QUEUE_SERVER: profileEnvFile = "env-configs/queue-server.env"; break;
                case STORAGE_SERVER: profileEnvFile = "env-configs/storage-server.env"; break;
                case SMOKE_TEST: profileEnvFile = "env-configs/peo.env"; break;
            }
            if (profileEnvFile != null) {
                loadEnvFile(profileEnvFile, env);
                loadEnvFile("../" + profileEnvFile, env);
                loadEnvFile("../../" + profileEnvFile, env);
            }
            // Re-apply network IPs to ensure localhost overrides are kept
            overrideWithNetworkIps(env);
        }
        
        AppConfig resolved = fromEnvironment(profile, env);
        logger.info("Effective MongoDB target: profile=" + profile
                + ", uri=" + SecurityLogger.sanitize(resolved.getMongoUri())
                + ", database=parking_db, TLS=" + resolved.isMongoTlsEnabled()
                + ", sources=process environment/.env/network-ips/profile env");
        return resolved;
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
        if ("REQUIRED_BUT_MISSING".equals(password)) {
            throw new IllegalStateException("Security Risk: RABBITMQ_PASSWORD is not configured in the environment. Falling back to hardcoded defaults is disabled.");
        }
        String virtualHost = readOrDefault(environment, "RABBITMQ_VHOST", DEFAULT_VHOST);
        boolean tlsEnabled = Boolean.parseBoolean(readOrDefault(
                environment,
                "RABBITMQ_TLS_ENABLED",
                String.valueOf(DEFAULT_TLS_ENABLED)));
        boolean rabbitMqTlsAllowInvalidHostnames = Boolean.parseBoolean(readOrDefault(
                environment,
                "RABBITMQ_TLS_ALLOW_INVALID_HOSTNAMES",
                "false"));
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

        String mongoPass = readOrDefault(environment, "MONGO_PASSWORD", activeProfile.defaultMongoPass());
        if ("REQUIRED_BUT_MISSING".equals(mongoPass)) {
            throw new IllegalStateException("Security Risk: MONGO_PASSWORD (or database password) is not configured. Falling back to hardcoded defaults is disabled.");
        }

        String mongoUri = environment.get("MONGO_URI");
        if (mongoUri == null || mongoUri.isBlank()) {
            mongoUri = String.format(DEFAULT_MONGO_URI, activeProfile.defaultMongoUser(), mongoPass);
        } else {
            if (mongoUri.startsWith("mongodb://")) {
                int atIndex = mongoUri.indexOf("@");
                if (atIndex > 0) {
                    String prefix = "mongodb://";
                    String rest = mongoUri.substring(atIndex);
                    mongoUri = prefix + activeProfile.defaultMongoUser() + ":" + mongoPass + rest;
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

        List<ClusterNode> recommenderNodes = parseRecommenderNodes(readOrDefault(environment, "RECOMMENDER_NODES", "localhost:8091,localhost:8092,localhost:8093"));

        String m1Ip = readOrDefault(environment, "MONGO1_IP", "127.0.0.1");
        String m2Ip = readOrDefault(environment, "MONGO2_IP", "127.0.0.1");
        String m3Ip = readOrDefault(environment, "MONGO3_IP", "127.0.0.1");

        return new AppConfig(
                nodes,
                username,
                password,
                virtualHost,
                tlsEnabled,
                rabbitMqTlsAllowInvalidHostnames,
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
                maxAllowedAmount,
                recommenderNodes,
                m1Ip,
                m2Ip,
                m3Ip);

    }

    /**
     * Returns the configured Recommender nodes.
     *
     * @return the recommender node list
     */
    public List<ClusterNode> getRecommenderNodes() {
        return recommenderNodes;
    }

    public String getMongo1Ip() {
        return mongo1Ip;
    }

    public String getMongo2Ip() {
        return mongo2Ip;
    }

    public String getMongo3Ip() {
        return mongo3Ip;
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
        return rabbitMqTlsAllowInvalidHostnames;
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
     * Returns the path to the TLS keystore (JKS) containing the server certificate.
     *
     * @return the server keystore file path
     */
    public String getTlsServerKeystorePath() {
        return tlsServerKeystorePath;
    }

    /**
     * Returns the password for the TLS server keystore.
     *
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

    /**
     * Reads a key from the environment map and returns the trimmed value, or the
     * supplied default when the key is absent or blank.
     *
     * @param environment  the environment variable map
     * @param key          the configuration key to look up
     * @param defaultValue the fallback value when the key is missing or blank
     * @return the resolved configuration value
     */
    private static String readOrDefault(Map<String, String> environment, String key, String defaultValue) {
        String val = environment.get(key);
        return (val != null && !val.trim().isEmpty()) ? val.trim() : defaultValue;
    }

    /**
     * Parses a positive integer from a raw string value.
     *
     * @param rawValue  the string to parse
     * @param fieldName the configuration field name used in error messages
     * @return the parsed positive integer
     * @throws IllegalArgumentException if the value is not a positive integer
     */
    private static int parsePositiveInt(String rawValue, String fieldName) {
        try {
            return ValidationUtils.requirePositive(Integer.parseInt(rawValue), fieldName);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(fieldName + " must be a positive integer.", ex);
        }
    }

    /**
     * Parses a positive long from a raw string value.
     *
     * @param rawValue  the string to parse
     * @param fieldName the configuration field name used in error messages
     * @return the parsed positive long
     * @throws IllegalArgumentException if the value is not a positive integer
     */
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

    /**
     * Parses a comma-separated list of {@code host:port} RabbitMQ node addresses.
     *
     * @param rawNodes the raw comma-separated node addresses
     * @return the parsed cluster node list
     */
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

    /**
     * Parses a comma-separated list of {@code host:port} recommender node addresses.
     *
     * @param rawNodes the raw comma-separated node addresses
     * @return the parsed recommender node list
     */
    private static List<ClusterNode> parseRecommenderNodes(String rawNodes) {
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
                throw new IllegalArgumentException("Invalid recommender node entry: " + trimmedPart);
            }
            String host = ValidationUtils.requireNonEmpty(addressParts[0], "recommenderNodeHost");
            int port = parsePositiveInt(addressParts[1], "recommenderNodePort");
            nodes.add(new ClusterNode(host, port, "recommender" + counter, true));
            counter++;
        }
        return nodes;
    }
}
