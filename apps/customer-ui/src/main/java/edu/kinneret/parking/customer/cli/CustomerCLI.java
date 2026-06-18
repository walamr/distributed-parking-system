package edu.kinneret.parking.customer.cli;

import edu.kinneret.parking.common.*;
import edu.kinneret.parking.customer.RecommenderRequestSigner;
import com.google.gson.JsonObject;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import javax.net.ssl.SSLSocket;

/**
 * Command-Line Interface for the Customer application.
 * Satisfies the requirement to provide both GUIs and CLIs.
 */
public class CustomerCLI {
    
    /**
     * Default constructor for CustomerCLI.
     */
    public CustomerCLI() {
    }

    private static final Logger logger = LoggerFactory.getLogger(CustomerCLI.class);
    private static String loggedInUser = null;
    private static String loggedInVin = null;

    /**
     * Starts the cluster-aware customer command-line interface.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        // Suppress noisy background database connection logs from java.util.logging
        java.util.logging.Logger.getLogger("edu.kinneret.parking.common.MongoConnectionManager").setLevel(java.util.logging.Level.WARNING);

        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.CUSTOMER_UI);
        RabbitMqConnectionManager rabbitManager = new RabbitMqConnectionManager(config);
        SecureMessageSigner signer = new SecureMessageSigner(config.getHmacSecret());

        System.out.println("==========================================");
        System.out.println("   MULLIGAN PARKING - CUSTOMER CLUSTER CLI");
        System.out.println("==========================================");

        try (ParkingRepository repository = new ParkingRepository(config);
             Scanner scanner = new Scanner(System.in)) {
            while (true) {
                if (loggedInUser == null) {
                    System.out.println("\nStatus: NOT LOGGED IN");
                    System.out.println("Options: [1] Login, [2] Exit");
                    System.out.print("Select: ");
                    String choice = scanner.nextLine();

                    if ("2".equals(choice)) break;

                    if ("1".equals(choice)) {
                        System.out.print("Enter Vehicle VIN: ");
                        String vin = scanner.nextLine().trim();

                        // Auto-format 8-digit numeric VIN into 000-00-000 format
                        if (vin.matches("\\d{8}")) {
                            vin = vin.substring(0, 3) + "-" + vin.substring(3, 5) + "-" + vin.substring(5);
                        }

                        if (vin.isEmpty()) {
                            System.out.println("ERROR: Vehicle VIN cannot be empty.");
                            continue;
                        }

                        try {
                            if (repository.isVehicleRegistered(vin)) {
                                String ownerName = "Customer";
                                if (repository.getDatabase() != null) {
                                    try {
                                        org.bson.Document vehicle = repository.getDatabase().getCollection("vehicles")
                                                .find(com.mongodb.client.model.Filters.eq("vehicleId", vin))
                                                .first();
                                        if (vehicle != null && vehicle.containsKey("owner")) {
                                            ownerName = vehicle.getString("owner");
                                        }
                                    } catch (Exception ignored) {}
                                }
                                loggedInUser = ownerName;
                                loggedInVin = vin;
                                // repository.registerUser(ownerName, vin, "customer", vin); // Bypassed to avoid database permission warnings for low-privilege customer role
                                System.out.println("SUCCESS: Authenticated with vehicle VIN '" + vin + "' (Owner: " + ownerName + ").");
                            } else {
                                System.out.println("ERROR: Authentication failed. VIN '" + vin + "' is not registered.");
                            }
                        } catch (Exception e) {
                            System.err.println("ERROR: Unable to connect to the database cluster for login. Please try again.");
                        }
                    } else {
                        System.out.println("Invalid choice.");
                    }
                } else {
                    System.out.println("\nStatus: LOGGED IN as '" + loggedInUser + "' (Vehicle VIN: '" + loggedInVin + "')");
                    System.out.println("Options: [1] Start Parking, [2] Stop Parking, [3] List Parking Events (History), [4] Recommend Parking, [5] Logout, [6] Exit");
                    System.out.print("Select: ");
                    String choice = scanner.nextLine();

                    if ("6".equals(choice)) break;

                    switch (choice) {
                        case "1":
                            System.out.print("Enter Space ID: ");
                            String spaceId = scanner.nextLine();
                            publishMessage(rabbitManager, config, signer, loggedInVin, spaceId, "start", repository);
                            break;
                        case "2":
                            System.out.print("Enter Space ID: ");
                            String stopSpaceId = scanner.nextLine();
                            publishMessage(rabbitManager, config, signer, loggedInVin, stopSpaceId, "stop", repository);
                            break;
                        case "3":
                            try {
                                List<Document> history = repository.getVehicleHistory(loggedInVin);
                                System.out.println("History for " + loggedInVin + " (" + history.size() + " records):");
                                for (Document doc : history) {
                                    System.out.println(" - " + doc.get("type") + " at " + doc.get("timestamp") + " in space " + ParkingRepository.readPayloadField(doc, "spaceId"));
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to retrieve history for VIN: {}", safeForLog(loggedInVin));
                                System.err.println("ERROR: Unable to connect to the database cluster. Please try again later.");
                            }
                            break;
                        case "4":
                            String spaceIdRec = "";
                            while (true) {
                                System.out.print("Enter Space ID to base recommendation on: ");
                                spaceIdRec = scanner.nextLine().trim();
                                if (spaceIdRec.isEmpty()) {
                                    System.out.println("ERROR: Space ID cannot be empty.");
                                    continue;
                                }
                                if (!spaceIdRec.matches("\\d+")) {
                                    System.out.println("ERROR: Space ID must be numeric.");
                                    continue;
                                }
                                try {
                                    ValidationUtils.requireValidSpaceId(spaceIdRec);
                                    int numericSpace = Integer.parseInt(spaceIdRec);
                                    if (numericSpace < 1 || numericSpace > 100) {
                                        System.out.println("ERROR: Space ID must be between 1 and 100.");
                                        continue;
                                    }
                                } catch (IllegalArgumentException e) {
                                    System.out.println("ERROR: Invalid space ID format.");
                                    continue;
                                }
                                break;
                            }
                            List<ClusterNode> recNodes = config.getRecommenderNodes();
                            System.out.println("Select Recommender Node:");
                            for (int i = 0; i < recNodes.size(); i++) {
                                System.out.println(" [" + (i + 1) + "] " + recNodes.get(i).getDisplayName() + " (" + recNodes.get(i).toAddress() + ")");
                            }
                            System.out.print("Select [Default 1]: ");
                            String nodeChoice = scanner.nextLine().trim();
                            int selectedIdx = 0;
                            try {
                                if (!nodeChoice.isEmpty()) {
                                    selectedIdx = Integer.parseInt(nodeChoice) - 1;
                                }
                            } catch (NumberFormatException ignored) {}
                            if (selectedIdx < 0 || selectedIdx >= recNodes.size()) {
                                selectedIdx = 0;
                            }
                            queryRecommender(spaceIdRec, recNodes, selectedIdx, config, signer);
                            break;
                        case "5":
                            System.out.println("Logging out customer '" + loggedInUser + "'.");
                            loggedInUser = null;
                            loggedInVin = null;
                            break;
                        default:
                            System.out.println("Invalid choice.");
                    }
                }
            }
        }
        System.out.println("Exiting CLI.");
    }

/**

 * Publish message.

 * @param manager the manager

 * @param config the config

 * @param signer the signer

 * @param vin the vin

 * @param spaceId the spaceId

 * @param type the type

 * @param repository the repository

 */

    private static void publishMessage(RabbitMqConnectionManager manager, AppConfig config, SecureMessageSigner signer, String vin, String spaceId, String type, ParkingRepository repository) {
        try {
            String payload = buildPayload(vin, spaceId, type, repository, config);
            String correlationId = UUID.randomUUID().toString();
            String clientIp = InetAddress.getLocalHost().getHostAddress();
            MessageEnvelope envelope = MessageEnvelope.createUnsigned("transaction." + type, payload, clientIp, correlationId).sign(signer);
            
            manager.withChannelForQueue(config.getTransactionsQueueName(), (channel, node) -> {
                channel.basicPublish("", config.getTransactionsQueueName(), null, envelope.toJsonString().getBytes(StandardCharsets.UTF_8));
                System.out.println("SUCCESS: Published " + type + " via cluster node: " + node.toAddress());
            });
        } catch (Exception e) {
            logger.warn("Failed to publish customer {} request to the cluster: {}", type, SecurityLogger.sanitize(e.getMessage()));
            System.err.println("ERROR: Request could not be processed. Please try again.");
        }
    }

/**

 * Build payload.

 * @param vin the vin

 * @param spaceId the spaceId

 * @param type the type

 * @param config the config

 * @return the string

 */

    static String buildPayload(String vin, String spaceId, String type, AppConfig config) {
        /**
         * Build payload.
         * @param vin the vin
         * @param spaceId the spaceId
         * @param type the type
         * @param null the null
         * @param config the config
         * @return the return
         */
        return buildPayload(vin, spaceId, type, null, config);
    }

/**

 * Build payload.

 * @param vin the vin

 * @param spaceId the spaceId

 * @param type the type

 * @param repository the repository

 * @param config the config

 * @return the string

 */

    static String buildPayload(String vin, String spaceId, String type, ParkingRepository repository, AppConfig config) {
        String safeVin = ValidationUtils.requireValidVehicleId(vin == null ? "" : vin.trim().toUpperCase());
        String safeSpace = ValidationUtils.requireValidSpaceId(spaceId == null ? "" : spaceId.trim().toUpperCase());
        String safeType = type == null ? "" : type.trim().toLowerCase();
        if (!"start".equals(safeType) && !"stop".equals(safeType)) {
            throw new IllegalArgumentException("Unsupported parking action.");
        }
        
        String costString = "0.00";
        if ("stop".equals(safeType)) {
            if (repository != null) {
                try {
                    List<Document> history = repository.getVehicleHistory(safeVin);
                    if (!history.isEmpty()) {
                        Document lastEvent = history.get(0);
                        if ("transaction.start".equals(lastEvent.getString("type"))) {
                            Long startTimestamp = lastEvent.getLong("timestamp");
                            if (startTimestamp != null) {
                                long elapsedSeconds = java.time.Instant.now().getEpochSecond() - startTimestamp;
                                if (elapsedSeconds < 0) elapsedSeconds = 0;
                                
                                java.math.BigDecimal rate = repository.getSpaceRate(safeSpace);
                                if (rate == null || rate.compareTo(java.math.BigDecimal.ZERO) == 0) {
                                    rate = new java.math.BigDecimal("12.5");
                                }
                                java.math.BigDecimal hours = java.math.BigDecimal.valueOf(elapsedSeconds)
                                        .divide(java.math.BigDecimal.valueOf(3600), 4, java.math.RoundingMode.HALF_UP);
                                costString = rate.multiply(hours).setScale(2, java.math.RoundingMode.HALF_UP).toString();
                            }
                        }
                    }
                } catch (Exception ex) {
                    System.out.println("Warning: Could not contact database to calculate exact cost. Using default cost of 0.00.");
                }
            } else {
                costString = "0.00";
            }
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("vehicleId", safeVin);
        payload.addProperty("spaceId", safeSpace);
        payload.addProperty("type", safeType);
        if ("stop".equals(safeType)) {
            payload.addProperty("cost", costString);
        }
        ValidationUtils.validateParkingPayload(payload.toString(), config.getMaxAllowedAmount(), "transaction." + safeType);
        return payload.toString();
    }

    /**
     * Sends a signed TLS recommendation query from the CLI.
     *
     * @param spaceId requested numeric parking space number
     * @param host recommender node host address
     * @param port recommender node TLS port
     * @param config application TLS configuration
     * @param signer HMAC-SHA256 signer
     */
    private static void queryRecommender(String spaceId, List<ClusterNode> recNodes, int selectedIdx, AppConfig config, SecureMessageSigner signer) {
        if (spaceId == null || spaceId.isBlank()) {
            System.out.println("ERROR: Space ID cannot be empty.");
            return;
        }
        if (!spaceId.matches("\\d+")) {
            System.out.println("ERROR: Space ID must be numeric.");
            return;
        }
        try {
            ValidationUtils.requireValidSpaceId(spaceId);
            int numericSpace = Integer.parseInt(spaceId);
            if (numericSpace < 1 || numericSpace > 100) {
                System.out.println("ERROR: Space ID must be between 1 and 100.");
                return;
            }
        } catch (IllegalArgumentException e) {
            System.out.println("ERROR: Invalid space ID format.");
            return;
        }

        JsonObject request = RecommenderRequestSigner.createSignedRequest(
                "CLIENT_QUERY",
                spaceId,
                UUID.randomUUID().toString(),
                "customer-cli",
                signer);

        javax.net.ssl.SSLContext sslContext = null;
        try {
            sslContext = TlsUtils.createSslContext(
                    config.getTlsTruststorePath(),
                    config.getTlsTruststorePassword(),
                    config.getTlsKeystorePath(),
                    config.getTlsKeystorePassword());
        } catch (Exception e) {
            logger.warn("Could not create SSL Context: {}", e.getMessage());
            System.out.println("ERROR: TLS configuration issue.");
            return;
        }

        boolean success = false;
        Exception lastEx = null;
        int numNodes = recNodes.size();

        for (int i = 0; i < numNodes; i++) {
            int currentIdx = (selectedIdx + i) % numNodes;
            ClusterNode node = recNodes.get(currentIdx);
            try (SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket()) {
                socket.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                socket.connect(new java.net.InetSocketAddress(node.getHost(), node.getPort()), 3000);
                socket.startHandshake();
                try (java.io.PrintWriter writer = new java.io.PrintWriter(socket.getOutputStream(), true);
                     java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()))) {
                    
                    writer.println(request.toString());
                    String responseLine = reader.readLine();
                    if (responseLine != null) {
                        com.google.gson.JsonObject response = com.google.gson.JsonParser.parseString(responseLine).getAsJsonObject();
                        String status = response.get("status").getAsString();
                        if ("SUCCESS".equalsIgnoreCase(status)) {
                            String result = response.get("result").getAsString();
                            System.out.println("RECOMMENDATION SUCCESS: Recommended spaces: " + result);
                        } else {
                            String reason = response.has("reason") ? response.get("reason").getAsString() : "Unknown error";
                            System.out.println("RECOMMENDATION FAILURE: " + reason);
                        }
                        success = true;
                        break;
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to query recommender node " + node.getDisplayName() + " at " + node.toAddress() + ": " + e.getMessage());
                lastEx = e;
            }
        }

        if (!success) {
            if (lastEx != null) {
                logger.warn("All recommender nodes failed. Last error: ", lastEx);
            }
            System.out.println("ERROR: Recommendation service temporarily unavailable.");
        }
    }

/**

 * Safe for log.

 * @param value the value

 * @return the string

 */

    private static String safeForLog(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
