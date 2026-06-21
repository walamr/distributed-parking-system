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

        System.out.println("\n+--------------------------------------------------+");
        System.out.println("|     MULLIGAN PARKING - CUSTOMER CLUSTER CLI      |");
        System.out.println("+--------------------------------------------------+");

        try (ParkingRepository repository = new ParkingRepository(config);
             Scanner scanner = new Scanner(System.in)) {
            while (true) {
                if (loggedInUser == null) {
                    System.out.println("\n==================================================");
                    System.out.println("  STATUS: NOT LOGGED IN");
                    System.out.println("==================================================");
                    System.out.println("  [1] Authenticate (Login via VIN)");
                    System.out.println("  [2] Exit CLI");
                    System.out.println("--------------------------------------------------");
                    System.out.print("Select Option > ");
                    String choice = scanner.nextLine().trim();

                    if ("2".equals(choice)) break;

                    if ("1".equals(choice)) {
                        System.out.print("Enter Vehicle VIN: ");
                        String vin = scanner.nextLine().trim();

                        // Auto-format 8-digit numeric VIN into 000-00-000 format
                        if (vin.matches("\\d{8}")) {
                            vin = vin.substring(0, 3) + "-" + vin.substring(3, 5) + "-" + vin.substring(5);
                        }

                        if (vin.isEmpty()) {
                            System.out.println("\n>>> ERROR: Vehicle VIN cannot be empty.");
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
                                System.out.println("\n>>> SUCCESS: Authenticated successfully!");
                                System.out.println("    Owner: " + ownerName + " | VIN: " + vin);
                            } else {
                                System.out.println("\n>>> ERROR: Authentication failed. VIN '" + vin + "' is not registered.");
                            }
                        } catch (Exception e) {
                            System.err.println("\n>>> ERROR: Unable to connect to the database cluster for login. Please try again.");
                        }
                    } else {
                        System.out.println("\n>>> ERROR: Invalid choice.");
                    }
                } else {
                    System.out.println("\n==================================================");
                    System.out.println("  LOGGED IN AS : " + loggedInUser);
                    System.out.println("  VEHICLE VIN  : " + loggedInVin);
                    System.out.println("==================================================");
                    System.out.println("  [1] Start Parking Session");
                    System.out.println("  [2] Stop Parking Session");
                    System.out.println("  [3] View Parking History");
                    System.out.println("  [4] Get Parking Recommendation");
                    System.out.println("  [5] Log Out");
                    System.out.println("  [6] Exit CLI");
                    System.out.println("--------------------------------------------------");
                    System.out.print("Select Option > ");
                    String choice = scanner.nextLine().trim();

                    if ("6".equals(choice)) break;

                    switch (choice) {
                        case "1":
                            System.out.print("Enter Target Space ID: ");
                            String spaceId = scanner.nextLine().trim();
                            System.out.println();
                            publishMessage(rabbitManager, config, signer, loggedInVin, spaceId, "start", repository);
                            break;
                        case "2":
                            System.out.print("Enter Active Space ID: ");
                            String stopSpaceId = scanner.nextLine().trim();
                            System.out.println();
                            publishMessage(rabbitManager, config, signer, loggedInVin, stopSpaceId, "stop", repository);
                            break;
                        case "3":
                            try {
                                List<Document> history = repository.getVehicleHistory(loggedInVin);
                                System.out.println("\n+--------------------------------------------------+");
                                System.out.println("|        PARKING HISTORY FOR " + String.format("%-17s", loggedInVin) + "     |");
                                System.out.println("+--------------------------------------------------+");
                                if (history.isEmpty()) {
                                    System.out.println("|  No records found.                               |");
                                } else {
                                    for (Document doc : history) {
                                        String type = doc.getString("type");
                                        String action = type != null && type.endsWith(".stop") ? "STOP " : "START";
                                        String space = ParkingRepository.readPayloadField(doc, "spaceId");
                                        Object ts = doc.get("timestamp");
                                        System.out.println(String.format("|  %s | Space: %-3s | Timestamp: %-15s |", action, space, ts != null ? ts.toString() : "-"));
                                    }
                                }
                                System.out.println("+--------------------------------------------------+");
                            } catch (Exception e) {
                                logger.warn("Failed to retrieve history for VIN: {}", safeForLog(loggedInVin));
                                System.err.println("\n>>> ERROR: Unable to connect to the database cluster. Please try again later.");
                            }
                            break;
                        case "4":
                            String spaceIdRec = "";
                            while (true) {
                                System.out.print("\nEnter Space ID to check: ");
                                spaceIdRec = scanner.nextLine().trim();
                                try {
                                    spaceIdRec = validateRecommendationSpaceId(spaceIdRec);
                                } catch (IllegalArgumentException e) {
                                    System.out.println(">>> ERROR: " + e.getMessage());
                                    continue;
                                }
                                break;
                            }
                            List<ClusterNode> recNodes = new java.util.ArrayList<>(config.getRecommenderNodes());
                            java.util.Collections.shuffle(recNodes);
                            System.out.println("\nQuerying recommender nodes...");
                            queryRecommender(spaceIdRec, recNodes, 0, config, signer);
                            break;
                        case "5":
                            System.out.println("\n>>> Logging out customer '" + loggedInUser + "'.");
                            loggedInUser = null;
                            loggedInVin = null;
                            break;
                        default:
                            System.out.println("\n>>> ERROR: Invalid choice.");
                    }
                }
            }
        }
        System.out.println("\nExiting CLI.");
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
            
            manager.withPublisherConfirms((channel, node) -> {
                channel.basicPublish("", config.getTransactionsQueueName(), null, envelope.toJsonString().getBytes(StandardCharsets.UTF_8));
            });
            System.out.println(publishSuccessMessage(type));
        } catch (Exception e) {
            logger.warn("Failed to publish customer {} request to the cluster: {}", type, SecurityLogger.sanitize(e.getMessage()));
            System.err.println("ERROR: Request could not be processed. Please try again.");
        }
    }

    static String publishSuccessMessage(String type) {
        return "SUCCESS: Parking " + type
                + " request was accepted by RabbitMQ. MongoDB persistence is pending server-side confirmation/logs.";
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
     * @param recNodes configured recommender nodes, beginning with the selected node
     * @param selectedIdx index of the node selected by the customer
     * @param config application TLS configuration
     * @param signer HMAC-SHA256 signer
     */
    static void queryRecommender(String spaceId, List<ClusterNode> recNodes, int selectedIdx, AppConfig config, SecureMessageSigner signer) {
        try {
            spaceId = validateRecommendationSpaceId(spaceId);
        } catch (IllegalArgumentException e) {
            System.out.println("ERROR: " + e.getMessage());
            return;
        }

        JsonObject request = RecommenderRequestSigner.createSignedRequest(
                "CLIENT_QUERY",
                spaceId,
                UUID.randomUUID().toString(),
                "customer-cli",
                loggedInVin,
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
                socket.setSoTimeout(3000);
                socket.startHandshake();
                try (java.io.PrintWriter writer = new java.io.PrintWriter(socket.getOutputStream(), true);
                     java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()))) {
                    
                     writer.println(request.toString());
                     String responseLine = reader.readLine();
                     if (responseLine != null) {
                         System.out.println(formatRecommendationResponse(responseLine, spaceId));
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
     * Validates the parking-space number accepted by the recommendation use case.
     *
     * @param spaceId raw CLI input
     * @return trimmed numeric space identifier
     */
    static String validateRecommendationSpaceId(String spaceId) {
        if (spaceId == null || spaceId.isBlank()) {
            throw new IllegalArgumentException("Space ID cannot be empty.");
        }
        String trimmed = spaceId.trim();
        if (!trimmed.matches("\\d+")) {
            throw new IllegalArgumentException("Space ID must be numeric.");
        }
        ValidationUtils.requireValidSpaceId(trimmed);
        int numericSpace = Integer.parseInt(trimmed);
        if (numericSpace < 1 || numericSpace > 100) {
            throw new IllegalArgumentException("Space ID must be between 1 and 100.");
        }
        return trimmed;
    }

    /**
     * Converts a recommender protocol response into the concise CLI display.
     * Overloaded to maintain backward compatibility with tests.
     *
     * @param responseLine one JSON response line, or null for a missing response
     * @return user-facing CLI text
     */
    static String formatRecommendationResponse(String responseLine) {
        if (responseLine == null || responseLine.isBlank()) {
            return "RECOMMENDATION FAILURE: No response from recommender node.";
        }
        try {
            JsonObject response = com.google.gson.JsonParser.parseString(responseLine).getAsJsonObject();
            if (!response.has("status")) {
                return "RECOMMENDATION FAILURE: Invalid response from recommender node.";
            }
            if ("SUCCESS".equalsIgnoreCase(response.get("status").getAsString())) {
                if (!response.has("result") || response.get("result").getAsString().isBlank()) {
                    return "RECOMMENDATION FAILURE: Invalid response from recommender node.";
                }
                return response.get("result").getAsString();
            }
            String reason = response.has("reason")
                    ? response.get("reason").getAsString()
                    : "Recommendation failed.";
            return "RECOMMENDATION FAILURE: " + reason;
        } catch (RuntimeException e) {
            return "RECOMMENDATION FAILURE: Invalid response from recommender node.";
        }
    }

    /**
     * Converts a recommender protocol response into the concise CLI display.
     *
     * @param responseLine one JSON response line, or null for a missing response
     * @param spaceId the user's chosen space number to check
     * @return user-facing CLI text
     */
    static String formatRecommendationResponse(String responseLine, String spaceId) {
        if (responseLine == null || responseLine.isBlank()) {
            return "RECOMMENDATION FAILURE: No response from recommender node.";
        }
        try {
            JsonObject response = com.google.gson.JsonParser.parseString(responseLine).getAsJsonObject();
            if (!response.has("status")) {
                return "RECOMMENDATION FAILURE: Invalid response from recommender node.";
            }
            if ("SUCCESS".equalsIgnoreCase(response.get("status").getAsString())) {
                if (!response.has("result") || response.get("result").getAsString().isBlank()) {
                    return "RECOMMENDATION FAILURE: Invalid response from recommender node.";
                }
                String result = response.get("result").getAsString();
                String[] parts = result.split("\n");
                if (parts.length >= 2) {
                    String rawResult = parts[1].replace("Result:", "").trim();
                    
                    // Check if the user's chosen spaceId matches one of the recommended spaces
                    boolean choseBest = false;
                    if (!"NONE".equalsIgnoreCase(rawResult)) {
                        String[] recommendationParts = rawResult.split(", ");
                        for (String part : recommendationParts) {
                            String cleanPart = part.replace("Space ", "").trim();
                            String[] spaceAndCitations = cleanPart.split(";");
                            if (spaceAndCitations.length >= 1 && spaceAndCitations[0].trim().equals(spaceId.trim())) {
                                choseBest = true;
                                break;
                            }
                        }
                    }
                    
                    // Format recommendation results
                    if ("NONE".equalsIgnoreCase(rawResult) || "Empty List".equalsIgnoreCase(rawResult)) {
                        return "+--------------------------------------------------+\n" +
                               "|  No recommendations available.                   |\n" +
                               "+--------------------------------------------------+";
                    }
                    String[] recommendationParts = rawResult.split(", ");
                    StringBuilder formatted = new StringBuilder();
                    formatted.append("+--------------------------------------------------+\n");
                    formatted.append("|              RECOMMENDATION RESULTS              |\n");
                    formatted.append("+--------------------------------------------------+\n");
                    if (choseBest) {
                        formatted.append("|  You chose the best space! [Excellent Choice]    |\n");
                    }
                    formatted.append("|  Recommendations:                                |\n");
                    for (int i = 0; i < recommendationParts.length; i++) {
                        String part = recommendationParts[i].replace("Space ", "").trim();
                        String[] spaceAndCitations = part.split(";");
                        if (spaceAndCitations.length >= 2) {
                            String item = String.format("   - Space %-3s (%s Citations)", spaceAndCitations[0], spaceAndCitations[1]);
                            formatted.append(String.format("|  %-46s  |", item));
                        } else {
                            String item = "   - " + recommendationParts[i];
                            formatted.append(String.format("|  %-46s  |", item));
                        }
                        if (i < recommendationParts.length - 1) {
                            formatted.append("\n");
                        }
                    }
                    formatted.append("\n+--------------------------------------------------+");
                    return formatted.toString();
                }
                return response.get("result").getAsString();
            }
            String reason = response.has("reason")
                    ? response.get("reason").getAsString()
                    : "Recommendation failed.";
            return "RECOMMENDATION FAILURE: " + reason;
        } catch (RuntimeException e) {
            return "RECOMMENDATION FAILURE: Invalid response from recommender node.";
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
