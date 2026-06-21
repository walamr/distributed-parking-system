// Cluster-aware parking repository for Stage 2
package edu.kinneret.parking.common;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bson.Document;
import org.bson.types.Decimal128;
import com.mongodb.ReadPreference;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-level repository for accessing parking data in the MongoDB cluster.
 */
public class ParkingRepository implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(ParkingRepository.class.getName());
    private final MongoConnectionManager connectionManager;
    private final MongoDatabase database;
    /**
     * Flag indicating whether the MongoDB cluster is currently online.
     */
    public static volatile boolean isDbOnline = true;

    private static final java.util.Set<AppConfig> activeConfigs = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static {
        Thread monitorThread = new Thread(() -> {
            while (true) {
                boolean anyOnline = false;
                java.util.List<AppConfig> configsToCheck = new java.util.ArrayList<>(activeConfigs);
                if (configsToCheck.isEmpty()) {
                    try {
                        configsToCheck.add(edu.kinneret.parking.common.AppConfig.fromEnvironment(edu.kinneret.parking.common.AppConfig.ApplicationProfile.MO_UI));
                    } catch (Exception ignored) {}
                }

                for (AppConfig config : configsToCheck) {
                    try (edu.kinneret.parking.common.ParkingRepository repo = new edu.kinneret.parking.common.ParkingRepository(config)) {
                        if (repo.getDatabase() != null) {
                            repo.getDatabase().runCommand(new org.bson.Document("ping", 1));
                            anyOnline = true;
                            break;
                        }
                    } catch (Exception e) {
                        // ignore and try next configuration
                    }
                }
                isDbOnline = anyOnline;
                try {
                    Thread.sleep(8000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "MulliganDbHealthMonitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    /**
     * Creates a repository backed by the configured MongoDB cluster.
     *
     * @param config the application configuration used to reach MongoDB
     */
    public ParkingRepository(AppConfig config) {
        if (config != null) {
            activeConfigs.add(config);
        }
        MongoConnectionManager tempMgr = null;
        MongoDatabase tempDb = null;
        try {
            tempMgr = new MongoConnectionManager(config, "parking_db");
            tempDb = tempMgr.getDatabase();
        } catch (Exception e) {
            System.err.println("Database connection manager initialization failed: " + e.getMessage());
        }
        this.connectionManager = tempMgr;
        this.database = tempDb;
    }

    /**
     * Extracts the numeric part of a space identifier string.
     *
     * @param spaceId the raw space identifier (e.g., "P101" or "42")
     * @return the parsed integer, or -1 if no numeric part can be found
     */
    private int parseSpaceNumber(String spaceId) {
        if (spaceId == null) return -1;
        String digits = spaceId.replaceAll("[^\\d]", "");
        if (digits.isEmpty()) return -1;
        try {
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Retrieves the hourly rate for a specific parking space.
     *
     * @param spaceId the space identifier
     * @return the hourly rate, or ZERO if not found
     */
    public BigDecimal getSpaceRate(String spaceId) {
        if (isDbOnline && database != null) {
            try {
                Document space = database.getCollection("spaces")
                        .withReadPreference(ReadPreference.secondaryPreferred())
                        .find(Filters.eq("spaceId", spaceId))
                        .first();
                if (space != null && space.containsKey("hourlyRate")) {
                    Object rate = space.get("hourlyRate");
                    if (rate instanceof Double) return BigDecimal.valueOf((Double) rate);
                    if (rate instanceof Decimal128) return ((Decimal128) rate).bigDecimalValue();
                }
            } catch (Exception e) {
                isDbOnline = false; // Trip the circuit breaker on cluster failure!
            }
        }
        try {
            int id = parseSpaceNumber(spaceId);
            if (id >= 1 && id <= 100) {
                double[] rates = {1.77, 70.23, 56.33, 24.36, 43.27, 35.37, 87.99, 56.22, 17.29, 55.27};
                return BigDecimal.valueOf(rates[(id - 1) % 10]);
            }
        } catch (Exception ignored) {}
        return BigDecimal.ZERO;
    }

    /**
     * Retrieves the zone name for a specific parking space.
     *
     * @param spaceId the space identifier
     * @return the zone name, or "Unknown"
     */
    public String getSpaceZone(String spaceId) {
        if (isDbOnline && database != null) {
            try {
                Document space = database.getCollection("spaces")
                        .withReadPreference(ReadPreference.secondaryPreferred())
                        .find(Filters.eq("spaceId", spaceId))
                        .first();
                if (space != null) {
                    return space.getString("zoneName");
                }
            } catch (Exception e) {
                isDbOnline = false; // Trip the circuit breaker on cluster failure!
            }
        }
        try {
            int id = parseSpaceNumber(spaceId);
            if (id >= 1 && id <= 100) {
                String[] zones = {"Magnolia Way", "Summit Ln", "Fifth Dr", "Downing Ave", "Elm Ct", "Central Way", "Queen St", "Main St", "Lansdowne Blvd", "Adams Ave"};
                return zones[(id - 1) % 10];
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    /**
     * Checks if a vehicle is legally parked in a specific space.
     * Logic: Finds the most recent transaction for the VIN. If it's a "start" and matches the spaceId, it's OK.
     *
     * @param vin the vehicle identifier to check
     * @param spaceId the parking space being inspected
     * @return the legality decision text for the UI
     */
    public String checkLegality(String vin, String spaceId) {
        // Step 1: Check if the vehicle VIN exists in the vehicles collection
        if (!isVehicleRegistered(vin)) {
            return "UNKNOWN_VIN";
        }
        // Step 2: Check if the parking space exists in the spaces collection
        if (!isSpaceRegistered(spaceId)) {
            return "UNKNOWN_SPACE";
        }
        // Step 3: Evaluate actual legality against the latest transaction
        Document lastTransaction = findLatestTransactionForVehicle(vin);
        if (lastTransaction == null) return "Parking Not Ok";
        return evaluateLegality(lastTransaction, spaceId);
    }

    /**
     * Checks whether a parking space with the given ID is registered in the database.
     *
     * @param spaceId the space identifier to check
     * @return true if the space exists in the spaces collection
     */
    public boolean isSpaceRegistered(String spaceId) {
        if (isDbOnline && database != null) {
            try {
                Document space = database.getCollection("spaces")
                        .withReadPreference(ReadPreference.secondaryPreferred())
                        .find(Filters.eq("spaceId", spaceId))
                        .first();
                return space != null;
            } catch (Exception e) {
                isDbOnline = false;
                System.err.println("Error checking space registration: " + e.getMessage());
            }
        }
        int id = parseSpaceNumber(spaceId);
        return id >= 1 && id <= 100;
    }

    /**
     * Retrieves the list of parking events for a specific vehicle.
     * Uses server-side filtering to minimize cluster network overhead.
     *
     * @param vehicleId the vehicle VIN
     * @return a list of documents representing parking events
     */
    public List<Document> getVehicleHistory(String vehicleId) {
        System.out.println("[DB-DEBUG] getVehicleHistory called for vehicleId: '" + vehicleId + "'");
        List<Document> history = new ArrayList<>();
        if (!isDbOnline || database == null) {
            System.out.println("[DB-DEBUG] database connection is offline or null!");
            return history;
        }
        try {
            database.getCollection("transactions")
                    .find(Filters.or(
                        Filters.eq("payload.vehicleId", vehicleId),
                        Filters.eq("vehicleId", vehicleId) // Support both schemas
                    ))
                    .sort(Sorts.orderBy(Sorts.descending("timestamp"), Sorts.descending("storedAt")))
                    .into(history);
            System.out.println("[DB-DEBUG] database query succeeded and returned " + history.size() + " documents.");
        } catch (Exception e) {
            System.out.println("[DB-DEBUG] database query failed with exception: " + e.getMessage());
            logger.log(Level.WARNING, "Error querying vehicle history from database: " + e.getMessage(), e);
        }
        return history;
    }

    /**
     * Checks whether a vehicle with the given VIN is registered in the database.
     *
     * @param vehicleId the vehicle VIN to check
     * @return true if the vehicle exists in the vehicles collection
     */
    public boolean isVehicleRegistered(String vehicleId) {
        if (isDbOnline && database != null) {
            try {
                Document vehicle = database.getCollection("vehicles")
                        .withReadPreference(ReadPreference.secondaryPreferred())
                        .find(Filters.eq("vehicleId", vehicleId))
                        .first();
                return vehicle != null;
            } catch (Exception e) {
                isDbOnline = false;
                System.err.println("Error checking vehicle registration: " + e.getMessage());
            }
        }
        String warning = "WARNING: MongoDB unavailable; using offline VIN validation fallback.";
        logger.warning(warning);
        System.err.println(warning);
        return vehicleId != null && vehicleId.matches("^[A-Z0-9-]{1,20}$");
    }

    /**
     * Retrieves all registered vehicles in the database.
     *
     * @return a list of documents representing all registered vehicles
     */
    public List<Document> getAllVehicles() {
        List<Document> results = new ArrayList<>();
        if (!isDbOnline || database == null) return results;
        try {
            database.getCollection("vehicles")
                    .withReadPreference(ReadPreference.secondaryPreferred())
                    .find()
                    .into(results);
        } catch (Exception e) {
            System.err.println("Error querying all vehicles from database: " + e.getMessage());
        }
        return results;
    }


    /**
     * Retrieves all recorded transactions.
     * Reads from the primary so newly persisted parking events are immediately visible.
     *
     * @return the ordered list of stored transaction documents
     */
    public List<Document> getAllTransactions() {
        List<Document> results = new ArrayList<>();
        if (!isDbOnline || database == null) return results;
        database.getCollection("transactions")
                .withReadPreference(ReadPreference.primary())
                .find()
                .sort(Sorts.orderBy(Sorts.descending("timestamp"), Sorts.descending("storedAt")))
                .into(results);
        return results;
    }

    /**
     * Checks the primary for a transaction persisted by the storage server.
     *
     * @param messageId the RabbitMQ envelope message identifier
     * @return true once the matching transaction document is visible on the primary
     */
    public boolean isTransactionPersisted(String messageId) {
        if (database == null || messageId == null || messageId.isBlank()) {
            return false;
        }
        return database.getCollection("transactions")
                .withReadPreference(ReadPreference.primary())
                .find(Filters.eq("messageId", messageId))
                .projection(new Document("_id", 1))
                .first() != null;
    }

    /**
     * Retrieves all recorded citations.
     * Uses ReadPreference.SECONDARY_PREFERRED to offload cluster load from the primary node.
     *
     * @return the ordered list of stored citation documents
     */
    public List<Document> getAllCitations() {
        List<Document> results = new ArrayList<>();
        if (!isDbOnline || database == null) return results;
        database.getCollection("citations")
                .withReadPreference(ReadPreference.secondaryPreferred())
                .find()
                .sort(Sorts.orderBy(Sorts.descending("timestamp"), Sorts.descending("storedAt")))
                .into(results);
        return results;
    }

    /**
     * Reads a payload field from a stored MongoDB document. The helper tolerates both
     * the current nested-document shape and the legacy string payload shape.
     *
     * @param record the stored MongoDB document
     * @param fieldName the payload field to extract
     * @return the field value when present, otherwise an empty string
     */
    public static String readPayloadField(Document record, String fieldName) {
        Document payloadDocument = readPayloadDocument(record);
        if (payloadDocument == null) {
            return "";
        }

        Object value = payloadDocument.get(fieldName);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Reads the payload object from a stored MongoDB document. The helper tolerates
     * both the nested-document representation and the legacy raw-string JSON
     * representation.
     *
     * @param record the stored MongoDB document
     * @return the parsed payload document, or {@code null} when no payload could be parsed
     */
    public static Document readPayloadDocument(Document record) {
        if (record == null) {
            return null;
        }

        Object rawPayload = record.get("payload");
        if (rawPayload instanceof Document payloadDocument) {
            return payloadDocument;
        }
        if (rawPayload instanceof String payloadString) {
            try {
                JsonObject payloadObject = JsonParser.parseString(payloadString).getAsJsonObject();
                return Document.parse(payloadObject.toString());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Extracts the transaction action (e.g., "start" or "stop") from a stored document.
     * Supports both modern nested structures and legacy formats.
     *
     * @param transactionRecord the stored transaction document
     * @return the transaction type string
     */
    public static String readTransactionAction(Document transactionRecord) {
        String payloadType = readPayloadField(transactionRecord, "type");
        if (!payloadType.isBlank()) {
            return payloadType;
        }

        String topLevelType = transactionRecord == null ? "" : transactionRecord.getString("type");
        if (topLevelType == null || topLevelType.isBlank()) {
            return "";
        }
        if (topLevelType.startsWith("transaction.")) {
            return topLevelType.substring("transaction.".length());
        }
        return topLevelType;
    }

    /**
     * Determines whether the given transaction record represents a legal parking
     * event for the requested space.
     *
     * @param transactionRecord the most recent transaction document for the vehicle
     * @param requestedSpaceId  the space that is being inspected
     * @return {@code "Parking Ok"} when the vehicle is actively parked in the
     *         requested space, otherwise {@code "Parking Not Ok"}
     */
    static String evaluateLegality(Document transactionRecord, String requestedSpaceId) {
        String type = readTransactionAction(transactionRecord);
        String recordedSpace = readPayloadField(transactionRecord, "spaceId");
        if ("start".equalsIgnoreCase(type) && requestedSpaceId.equalsIgnoreCase(recordedSpace)) {
            return "Parking Ok";
        }
        return "Parking Not Ok";
    }

    /**
     * Retrieves the latest transaction for a specific space ID.
     *
     * @param spaceId the parking space identifier
     * @return the latest transaction document, or null if none
     */
    public Document getLatestTransactionForSpace(String spaceId) {
        if (!isDbOnline || database == null) {
            return null;
        }
        try {
            List<Document> results = new ArrayList<>();
            database.getCollection("transactions")
                    .find(Filters.or(
                        Filters.eq("payload.spaceId", spaceId),
                        Filters.eq("spaceId", spaceId)
                    ))
                    .sort(Sorts.orderBy(Sorts.descending("timestamp"), Sorts.descending("storedAt")))
                    .limit(1)
                    .into(results);
            return results.isEmpty() ? null : results.getFirst();
        } catch (Exception e) {
            System.err.println("Error querying latest transaction for space: " + e.getMessage());
            return null;
        }
    }

    /**
     * Finds the most recent transaction document for a given vehicle.
     *
     * @param vehicleId the vehicle VIN to look up
     * @return the latest transaction document, or {@code null} if none exists
     */
    private Document findLatestTransactionForVehicle(String vehicleId) {
        List<Document> history = getVehicleHistory(vehicleId);
        return history.isEmpty() ? null : history.getFirst();
    }

    /**
     * Checks the health of the MongoDB cluster.
     * @return the cluster status document
     */
    public Document getClusterStatus() {
        return connectionManager.getClusterStatus();
    }

    /**
     * Logs a system query (e.g., PEO inspection) to the database.
     * @param queryLog the document containing query details
     */
    public void logSystemQuery(Document queryLog) {
        database.getCollection("system_log").insertOne(queryLog);
    }

    /**
     * Stores a validated message envelope in the appropriate collection.
     * Handles idempotency by ignoring duplicate message IDs.
     *
     * @param envelope the message to store
     */
    public void storeMessage(MessageEnvelope envelope) {
        String collectionName = envelope.getType().toLowerCase().contains("transaction") ? "transactions" : "citations";
        com.mongodb.client.MongoCollection<Document> collection = database.getCollection(collectionName);
        Object payloadValue = toMongoPayloadValue(envelope.getPayload());

        Document doc = new Document("messageId", envelope.getMessageId().toString())
                .append("type", envelope.getType())
                .append("timestamp", envelope.getTimestampEpochSeconds())
                .append("payload", payloadValue)
                .append("storedAt", System.currentTimeMillis());

        try {
            collection.insertOne(doc);
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getError().getCategory() != com.mongodb.ErrorCategory.DUPLICATE_KEY) {
                throw e;
            }
        }
    }

    /**
     * Converts a JSON payload string into a MongoDB-native value.
     * If the payload is a valid JSON object it is stored as an embedded document;
     * otherwise the raw string is stored as-is.
     *
     * @param payloadJson the payload text to convert
     * @return a {@link Document} when the payload is a JSON object, or the original string
     */
    private static Object toMongoPayloadValue(String payloadJson) {
        try {
            com.google.gson.JsonElement parsedPayload = com.google.gson.JsonParser.parseString(payloadJson);
            if (parsedPayload.isJsonObject()) {
                com.google.gson.JsonObject payloadObject = parsedPayload.getAsJsonObject();
                return Document.parse(payloadObject.toString());
            }
        } catch (Exception ignored) {
        }
        return payloadJson;
    }

    /**
     * Authenticates a user against the MongoDB cluster "users" collection.
     * Synchronizes dynamic credentials into local cache map if successful.
     *
     * @param username the username to authenticate
     * @param password the password to check
     * @return true if authentication succeeds
     */
    public boolean authenticateUser(String username, String password) {
        if (database == null) return false;
        try {
            Document user = database.getCollection("users")
                    .find(Filters.eq("username", username))
                    .first();
            if (user != null) {
                String storedPass = user.getString("password");
                if (storedPass != null && storedPass.equals(password)) {
                    edu.kinneret.parking.common.ui.LoginController.signupPasswords.put(username, password);
                    edu.kinneret.parking.common.ui.LoginController.signupRoles.put(username, user.getString("role"));
                    if (user.containsKey("vin")) {
                        edu.kinneret.parking.common.ui.LoginController.signupVins.put(username, user.getString("vin"));
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            System.err.println("Error authenticating user in database: " + e.getMessage());
        }
        return false;
    }

    /**
     * Registers a new user in the MongoDB cluster "users" collection.
     *
     * @param username the username to register
     * @param password the password to set
     * @param role the user role
     * @param vin the associated vehicle VIN (optional)
     * @return true if registration succeeds
     */
    public boolean registerUser(String username, String password, String role, String vin) {
        if (database == null) return false;
        try {
            Document userDoc = new Document("username", username)
                    .append("password", password)
                    .append("role", role)
                    .append("vin", vin != null ? vin : "")
                    .append("createdAt", System.currentTimeMillis());
            
            database.getCollection("users").replaceOne(
                Filters.eq("username", username),
                userDoc,
                new com.mongodb.client.model.ReplaceOptions().upsert(true)
            );
            return true;
        } catch (Exception e) {
            System.err.println("Error registering user in database: " + e.getMessage());
        }
        return false;
    }

    /**
     * Returns the underlying MongoDB database client instance.
     *
     * @return the MongoDatabase instance
     */
    public MongoDatabase getDatabase() {
        return database;
    }

    /**
     * Closes the repository and the underlying MongoDB connection manager.
     */
    @Override
    public void close() {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }
}
