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
                    builder.context(TlsUtils.createSslContext(
                        config.getTlsTruststorePath(),
                        config.getTlsTruststorePassword(),
                        config.getTlsKeystorePath(),
                        config.getTlsKeystorePassword()
                    ));
                } catch (Exception ex) {
                    logger.severe("Could not load certificates for mTLS. TLS context might be invalid: " + ex.getMessage());
                }
            });
        }

        // --- CLUSTER OPTIMIZATIONS (Hardening R2.1-Database-Cluster) ---
        // Ensure writes are acknowledged by a majority of nodes to prevent data loss on
        // failover
        settingsBuilder.writeConcern(WriteConcern.MAJORITY);
        // Default to reading from primary, but allow failover to secondaries
        settingsBuilder.readPreference(ReadPreference.primaryPreferred());

        // Fast timeouts for fail-fast behavior (2000 milliseconds selection and socket timeout)
        settingsBuilder.applyToClusterSettings(builder -> 
            builder.serverSelectionTimeout(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        );
        settingsBuilder.applyToSocketSettings(builder ->
            builder.connectTimeout(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
                   .readTimeout(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        );

        com.mongodb.client.MongoClient clientTemp = null;
        try {
            clientTemp = MongoClients.create(settingsBuilder.build());
        } catch (Exception e) {
            logger.severe("Failed to initialize MongoClient: " + SecurityLogger.sanitize(e.getMessage()));
        }
        this.mongoClient = clientTemp;
        logger.info(
                "Connected to MongoDB cluster: " + SecurityLogger.sanitize(config.getMongoUri()) + " (TLS=" + config.isMongoTlsEnabled() + ")");
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
