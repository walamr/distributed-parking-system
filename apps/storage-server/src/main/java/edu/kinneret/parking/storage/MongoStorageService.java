package edu.kinneret.parking.storage;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.MessageEnvelope;
import edu.kinneret.parking.common.MongoConnectionManager;
import org.bson.Document;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.logging.Logger;

/**
 * Handles persistence of parking messages to MongoDB.
 */
public class MongoStorageService {
    private static final Logger logger = Logger.getLogger(MongoStorageService.class.getName());
    private final MongoConnectionManager connectionManager;
    private final MongoDatabase database;

    /**
     * Initializes a connection to the MongoDB cluster and ensures required indexes exist.
     *
     * @param appConfig the application configuration containing connection details
     * @param dbName the name of the database to use
     */
    public MongoStorageService(AppConfig appConfig, String dbName) {
        this.connectionManager = new MongoConnectionManager(appConfig, dbName);
        this.database = connectionManager.getDatabase();
        ensureIndexes();
    }

    /**
     * Ensures that the collections have the required indexes for performance and idempotency.
     */
    private void ensureIndexes() {
        String[] collections = {"transactions", "citations"};
        for (String collName : collections) {
            MongoCollection<Document> coll = database.getCollection(collName);
            // Idempotency: Unique index on messageId prevents duplicates from retries
            coll.createIndex(new Document("messageId", 1), new com.mongodb.client.model.IndexOptions().unique(true));
            // Performance: Index on vehicleId for history lookups
            coll.createIndex(new Document("payload.vehicleId", 1));
            coll.createIndex(new Document("vehicleId", 1)); // Legacy support
            // Performance: Index on timestamp for sorting
            coll.createIndex(new Document("timestamp", -1));
        }
        // Metadata: Index on spaces
        database.getCollection("spaces").createIndex(new Document("spaceId", 1));
        logger.info("MongoDB cluster indexes verified/created for high-availability performance.");
    }

    /**
     * Stores a validated message envelope in the appropriate collection.
     * Handles idempotency by ignoring duplicate message IDs.
     *
     * @param envelope the message to store
     */
    public void storeMessage(MessageEnvelope envelope) {
        String collectionName = envelope.getType().toLowerCase().contains("transaction") ? "transactions" : "citations";
        MongoCollection<Document> collection = database.getCollection(collectionName);
        Object payloadValue = toMongoPayloadValue(envelope.getPayload());

        Document doc = new Document("messageId", envelope.getMessageId().toString())
                .append("type", envelope.getType())
                .append("timestamp", envelope.getTimestampEpochSeconds())
                .append("payload", payloadValue)
                .append("storedAt", System.currentTimeMillis());

        try {
            collection.insertOne(doc);
            logger.info("Stored " + envelope.getType() + " message [" + envelope.getMessageId() + "] in MongoDB");
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getError().getCategory() == com.mongodb.ErrorCategory.DUPLICATE_KEY) {
                logger.warning("Ignored duplicate message [" + envelope.getMessageId() + "] (Idempotency check passed)");
            } else {
                throw e;
            }
        }
    }

    static Object toMongoPayloadValue(String payloadJson) {
        try {
            JsonElement parsedPayload = JsonParser.parseString(payloadJson);
            if (parsedPayload.isJsonObject()) {
                JsonObject payloadObject = parsedPayload.getAsJsonObject();
                return Document.parse(payloadObject.toString());
            }
        } catch (Exception ignored) {
            // Backward-compatible fallback handled below.
        }
        return payloadJson;
    }

    /**
     * Closes the MongoDB client connection.
     */
    public void close() {
        connectionManager.close();
    }
}
