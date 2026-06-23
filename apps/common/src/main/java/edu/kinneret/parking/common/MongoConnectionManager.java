// Integrated with MongoDB cluster for Stage 2
package edu.kinneret.parking.common;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.MongoClientSettings;
import com.mongodb.ConnectionString;
import com.mongodb.WriteConcern;
import com.mongodb.ReadPreference;
import java.util.logging.Logger;

/**
 * Manages connections to the MongoDB cluster with TLS support.
 */
public final class MongoConnectionManager implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(MongoConnectionManager.class.getName());
    private final MongoClient mongoClient;
    private final String databaseName;

    /**
     * Initializes a connection to the MongoDB cluster using the provided
     * configuration.
     *
     * @param config       the application configuration
     * @param databaseName the name of the database to connect to
     */
    public MongoConnectionManager(AppConfig config, String databaseName) {
        this.databaseName = databaseName;
        ConnectionString connectionString = new ConnectionString(config.getMongoUri());
        MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder()
                .applyConnectionString(connectionString);

        if (config.isMongoTlsEnabled()) {
            settingsBuilder.applyToSslSettings(builder -> {
                builder.enabled(true);
                builder.invalidHostNameAllowed(config.isMongoTlsAllowInvalidHostnames());
                try {
                    logger.info("Initializing MongoDB TLS using CA PEM from path: " + config.getMongoTlsCaCertPath());
                    builder.context(TlsUtils.createTrustOnlySslContextFromPem(
                        config.getMongoTlsCaCertPath()
                    ));
                } catch (Exception e) {
                    logger.log(java.util.logging.Level.SEVERE, "Could not load MongoDB TLS CA certificate. TLS context might be invalid: " + e.getMessage(), e);
                }
            });
        }

        // --- CLUSTER OPTIMIZATIONS (Hardening R2.1-Database-Cluster) ---
        // Ensure writes are acknowledged by a majority of nodes to prevent data loss on
        // failover
        settingsBuilder.writeConcern(WriteConcern.MAJORITY);
        // Default to reading from primary, but allow failover to secondaries
        settingsBuilder.readPreference(ReadPreference.primaryPreferred());

        // Keep startup tolerant enough for a freshly initialized Docker replica set, and
        // long enough to ride through a primary election when one node is stopped. The
        // replica-set election can take ~10-12s to detect a downed primary, so a 10s
        // window risks failing writes mid-failover; 30s lets the driver wait for the new
        // primary and then complete the (retryable) write instead of erroring out.
        settingsBuilder.applyToClusterSettings(builder ->
            builder.serverSelectionTimeout(30000, java.util.concurrent.TimeUnit.MILLISECONDS)
        );
        settingsBuilder.applyToSocketSettings(builder ->
            builder.connectTimeout(10000, java.util.concurrent.TimeUnit.MILLISECONDS)
                   .readTimeout(10000, java.util.concurrent.TimeUnit.MILLISECONDS)
        );

        // For developers running apps without hosts modification:
        // Map container hostnames 'mongo1', 'mongo2', 'mongo3' to the IPs configured in network-ips.env.
        settingsBuilder.inetAddressResolver(new com.mongodb.spi.dns.InetAddressResolver() {
            @Override
            public java.util.List<java.net.InetAddress> lookupByName(String host) throws java.net.UnknownHostException {
                if ("mongo1".equalsIgnoreCase(host)) {
                    return java.util.Arrays.asList(java.net.InetAddress.getByName(config.getMongo1Ip()));
                } else if ("mongo2".equalsIgnoreCase(host)) {
                    return java.util.Arrays.asList(java.net.InetAddress.getByName(config.getMongo2Ip()));
                } else if ("mongo3".equalsIgnoreCase(host)) {
                    return java.util.Arrays.asList(java.net.InetAddress.getByName(config.getMongo3Ip()));
                }
                return java.util.Arrays.asList(java.net.InetAddress.getAllByName(host));
            }
        });

        com.mongodb.client.MongoClient clientTemp = null;
        try {
            clientTemp = MongoClients.create(settingsBuilder.build());
        } catch (Exception e) {
            logger.severe("Failed to initialize MongoClient: " + SecurityLogger.sanitize(e.getMessage()));
        }
        this.mongoClient = clientTemp;
        logger.info("MongoDB client configured: target=" + SecurityLogger.sanitize(config.getMongoUri())
                + ", database=" + databaseName + ", TLS=" + config.isMongoTlsEnabled());
    }

    /**
     * Returns the requested database instance.
     *
     * @return the MongoDatabase instance
     */
    public MongoDatabase getDatabase() {
        return mongoClient.getDatabase(databaseName);
    }

    /**
     * Retrieves the status of the MongoDB cluster.
     * 
     * @return a Document containing replica set status, or null if unreachable
     */
    public org.bson.Document getClusterStatus() {
        try {
            return mongoClient.getDatabase("admin").runCommand(new org.bson.Document("replSetGetStatus", 1));
        } catch (Exception e) {
            logger.severe("MongoDB Cluster Status Check Failed: " + SecurityLogger.sanitize(e.getMessage()));
            return null;
        }
    }

    /**
     * Closes the underlying MongoDB client connection.
     */
    @Override
    public void close() {
        mongoClient.close();
    }
}
