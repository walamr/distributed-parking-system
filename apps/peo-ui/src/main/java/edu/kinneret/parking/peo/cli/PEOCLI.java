package edu.kinneret.parking.peo.cli;

import edu.kinneret.parking.common.*;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import org.bson.Document;

/**
 * Command-Line Interface for the PEO application.
 * Satisfies the requirement to provide both GUIs and CLIs.
 */
public class PEOCLI {
    /**
     * Default constructor for PEOCLI.
     */
    public PEOCLI() {
        // Default constructor
    }

    private static final Logger logger = LoggerFactory.getLogger(PEOCLI.class);
    private static String loggedInOfficer = null;
    private static final List<PEOActivityLogEntry> sessionActivities = Collections.synchronizedList(new ArrayList<>());

    /**
     * Starts the cluster-aware PEO command-line interface.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        // Suppress noisy background database connection logs from java.util.logging
        java.util.logging.Logger.getLogger("edu.kinneret.parking.common.MongoConnectionManager").setLevel(java.util.logging.Level.WARNING);

        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.PEO_UI);
        RabbitMqConnectionManager rabbitManager = new RabbitMqConnectionManager(config);
        SecureMessageSigner signer = new SecureMessageSigner(config.getHmacSecret());

        System.out.println("==========================================");
        System.out.println("     MULLIGAN PARKING - PEO CLUSTER CLI");
        System.out.println("==========================================");
        System.out.println("RabbitMQ target: " + config.toRedactedSummary()
                + ", publisherConfirmsEnabled=true, consumerManualAckEnabled=false");

        try (ParkingRepository repository = new ParkingRepository(config);
             Scanner scanner = new Scanner(System.in)) {
            while (true) {
                if (loggedInOfficer == null) {
                    System.out.println("\nStatus: NOT LOGGED IN");
                    System.out.println("Options: [1] Login, [2] Exit");
                    System.out.print("Select: ");
                    String choice = scanner.nextLine();

                    if ("2".equals(choice)) break;

                    if ("1".equals(choice)) {
                        System.out.print("Enter Officer ID (Username): ");
                        String officerId = scanner.nextLine().trim();
                        System.out.print("Enter Password: ");
                        String password = scanner.nextLine();

                        if (officerId.isEmpty()) {
                            System.out.println("ERROR: Officer ID cannot be empty.");
                            continue;
                        }

                        try {
                            if (repository.authenticateUser(officerId, password)) {
                                loggedInOfficer = officerId;
                                System.out.println("SUCCESS: Authenticated as officer '" + officerId + "'.");
                            } else {
                                System.out.println("ERROR: Invalid Officer ID or password. Please try again.");
                            }
                        } catch (Exception e) {
                            System.err.println("ERROR: Unable to connect to the database cluster for login. Please try again.");
                        }
                    } else {
                        System.out.println("Invalid choice.");
                    }
                } else {
                    System.out.println("\nStatus: LOGGED IN as Officer '" + loggedInOfficer + "'");
                    System.out.println("Options: [1] Check Legality, [2] Issue Citation, [3] View Activity History, [4] List Registered Vehicles, [5] Logout, [6] Exit");
                    System.out.print("Select: ");
                    String choice = scanner.nextLine();

                    if ("6".equals(choice)) break;

                    switch (choice) {
                        case "1":
                            System.out.print("Enter Vehicle VIN: ");
                            String checkVin = scanner.nextLine();
                            System.out.print("Enter Space ID: ");
                            String checkSpaceId = scanner.nextLine();
                            try {
                                String result = repository.checkLegality(checkVin, checkSpaceId);
                                
                                // Record legality check activity in the session log
                                sessionActivities.add(new PEOActivityLogEntry("CHECK", checkVin, result, System.currentTimeMillis()));

                                if (ParkingRepository.isDbOnline && repository.getDatabase() != null) {
                                    try {
                                        org.bson.Document queryLog = new org.bson.Document("timestamp", java.time.Instant.now().toString())
                                                .append("vehicleId", checkVin.toUpperCase())
                                                .append("spaceId", checkSpaceId.toUpperCase())
                                                .append("response", result);
                                        repository.logSystemQuery(queryLog);
                                    } catch (Exception e) {
                                        // Ignore logging failure to avoid blocking user
                                    }
                                }

                                System.out.println("\n+--------------------------------------------------+");
                                System.out.println("|                  LEGALITY CHECK                  |");
                                System.out.println("+--------------------------------------------------+");
                                System.out.println(String.format("|  %-46s  |", "Vehicle VIN  : " + checkVin.toUpperCase()));
                                System.out.println(String.format("|  %-46s  |", "Space ID     : " + checkSpaceId.toUpperCase()));
                                System.out.println(String.format("|  %-46s  |", "Result       : " + result));
                                System.out.println("+--------------------------------------------------+");
                            } catch (Exception e) {
                                logger.warn("Failed to check legality for VIN: {}", safeForLog(checkVin));
                                System.err.println("ERROR: Unable to connect to the database cluster. Please try again later.");
                            }
                            break;
                        case "2":
                            System.out.print("Enter Vehicle VIN: ");
                            String citeVin = scanner.nextLine();
                            System.out.print("Enter Space ID: ");
                            String citeSpaceId = scanner.nextLine();
                            System.out.print("Enter Citation Amount: ");
                            String amount = scanner.nextLine();
                            System.out.print("Enter Reason: ");
                            String reason = scanner.nextLine();
                            issueCitation(rabbitManager, config, signer, citeVin, citeSpaceId, amount, reason, loggedInOfficer);
                            break;
                        case "3":
                            List<PEOActivityLogEntry> list = new ArrayList<>();
                            
                            // 1. Fetch transaction history from the database
                            try {
                                List<Document> transactions = repository.getAllTransactions();
                                for (Document doc : transactions) {
                                    String type = doc.getString("type");
                                    String action = "transaction.stop".equals(type) || "stop".equals(type) ? "PARKING STOP" : "PARKING START";
                                    String vehicle = ParkingRepository.readPayloadField(doc, "vehicleId");
                                    String space = ParkingRepository.readPayloadField(doc, "spaceId");
                                    long ts = getTimestampMs(doc);
                                    list.add(new PEOActivityLogEntry(action, vehicle, "Space " + space, ts));
                                }
                            } catch (Exception e) {
                                System.err.println("WARNING: Unable to load transaction history from the database.");
                            }

                            // 2. Merge with session activities
                            synchronized (sessionActivities) {
                                list.addAll(sessionActivities);
                            }

                            // 3. Sort by timestamp descending (newest first)
                            list.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

                            // 4. Render output table
                            System.out.println("\n+--------------------------------------------------+");
                            System.out.println("|                 ACTIVITY HISTORY                 |");
                            System.out.println("+----------------+-------------+-------------------+");
                            System.out.println(String.format("| %-14s | %-11s | %-17s |", "Action", "VIN", "Result"));
                            System.out.println("+----------------+-------------+-------------------+");
                            if (list.isEmpty()) {
                                System.out.println("|  No activities found.                            |");
                                System.out.println("+--------------------------------------------------+");
                            } else {
                                for (PEOActivityLogEntry entry : list) {
                                    System.out.print(String.format("| %-14s | %-11s | %-17s |\n",
                                            entry.action,
                                            entry.vin == null ? "" : entry.vin.toUpperCase(),
                                            entry.result));
                                }
                                System.out.println("+----------------+-------------+-------------------+");
                            }
                            break;
                        case "4":
                            try {
                                List<Document> vehicles = repository.getAllVehicles();
                                System.out.println("Registered Vehicles in System (" + vehicles.size() + " found):");
                                for (Document vehicle : vehicles) {
                                    System.out.println(" - VIN: " + vehicle.get("vehicleId") + " (Owner: " + vehicle.get("owner") + ", Type: " + vehicle.get("accountType") + ")");
                                }
                            } catch (Exception e) {
                                System.err.println("ERROR: Unable to retrieve vehicles list from the database.");
                            }
                            break;
                        case "5":
                            System.out.println("Logging out officer '" + loggedInOfficer + "'.");
                            loggedInOfficer = null;
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
     * Builds, signs, and publishes a citation request to the RabbitMQ cluster
     * with publisher confirms, then prints a confirmation summary to the console.
     *
     * @param manager   the RabbitMQ connection manager used to publish the request
     * @param config    the application configuration providing the target queue name
     * @param signer    the message signer used to authenticate the citation envelope
     * @param vin       the vehicle identification number being cited
     * @param spaceId   the parking space identifier where the violation occurred
     * @param amount    the citation amount as entered by the officer
     * @param reason    the textual reason for the citation
     * @param officerId the identifier of the officer issuing the citation
     */
     private static void issueCitation(RabbitMqConnectionManager manager, AppConfig config, SecureMessageSigner signer, String vin, String spaceId, String amount, String reason, String officerId) {
         try {
             String payload = buildCitationPayload(vin, spaceId, amount, reason, officerId, config);
             String correlationId = UUID.randomUUID().toString();
             String clientIp = InetAddress.getLocalHost().getHostAddress();
             MessageEnvelope envelope = MessageEnvelope.createUnsigned("citation.issue", payload, clientIp, correlationId).sign(signer);
             
             String[] publishNode = new String[] {"unknown"};
             manager.withPublisherConfirmsForQueue(config.getCitationsQueueName(), (channel, node) -> {
                 publishNode[0] = node.toAddress();
                 channel.basicPublish(
                         "",
                         config.getCitationsQueueName(),
                         com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN,
                         envelope.toJsonString().getBytes(StandardCharsets.UTF_8));
             });

             String displayReason = reason == null ? "" : reason;
             if (displayReason.length() > 30) {
                 displayReason = displayReason.substring(0, 27) + "...";
             }

             System.out.println("\n+--------------------------------------------------+");
             System.out.println("|                CITATION REQUEST                  |");
             System.out.println("+--------------------------------------------------+");
             System.out.println(String.format("|  %-46s  |", "Vehicle VIN  : " + (vin == null ? "" : vin.toUpperCase())));
             System.out.println(String.format("|  %-46s  |", "Space ID     : " + (spaceId == null ? "" : spaceId.toUpperCase())));
             System.out.println(String.format("|  %-46s  |", "Amount       : " + (amount == null ? "" : amount)));
             System.out.println(String.format("|  %-46s  |", "Reason       : " + displayReason));
             System.out.println(String.format("|  %-46s  |", "Status       : ACCEPTED BY RABBITMQ via " + publishNode[0]));
             System.out.println("+--------------------------------------------------+");

             // Record citation activity in the session log after broker confirmation.
             sessionActivities.add(new PEOActivityLogEntry("CITATION", vin, "Accepted by RabbitMQ", System.currentTimeMillis()));
         } catch (Exception e) {
             logger.warn("Failed to publish citation request to the cluster: {}", SecurityLogger.sanitize(e.getMessage()));
             System.err.println("ERROR: Request could not be processed. Please try again.");
         }
     }
 
    /**
     * Builds a validated JSON citation payload without an associated officer
     * identifier by delegating to the full overload with an empty officer value.
     *
     * @param vin     the vehicle identification number being cited
     * @param spaceId the parking space identifier where the violation occurred
     * @param amount  the citation amount as a string to be parsed and validated
     * @param reason  the textual reason for the citation
     * @param config  the application configuration providing validation limits
     * @return the validated citation payload serialized as a JSON string
     */
     static String buildCitationPayload(String vin, String spaceId, String amount, String reason, AppConfig config) {
         return buildCitationPayload(vin, spaceId, amount, reason, "", config);
     }

    /**
     * Validates the supplied citation fields, normalizes the vehicle and space
     * identifiers, parses and range-checks the amount, and assembles the result
     * into a validated JSON citation payload.
     *
     * @param vin       the vehicle identification number being cited
     * @param spaceId   the parking space identifier where the violation occurred
     * @param amount    the citation amount as a string to be parsed and validated
     * @param reason    the textual reason for the citation
     * @param officerId the identifier of the officer issuing the citation; omitted
     *                  from the payload when blank
     * @param config    the application configuration providing validation limits
     * @return the validated citation payload serialized as a JSON string
     * @throws IllegalArgumentException if the amount is not numeric or any field
     *                                  fails validation
     */
     static String buildCitationPayload(String vin, String spaceId, String amount, String reason, String officerId, AppConfig config) {
         String safeVin = ValidationUtils.requireValidVehicleId(vin == null ? "" : vin.trim().toUpperCase());
         String safeSpace = ValidationUtils.requireValidSpaceId(spaceId == null ? "" : spaceId.trim().toUpperCase());
         double parsedAmount;
         try {
             parsedAmount = Double.parseDouble(amount == null ? "" : amount.trim());
         } catch (NumberFormatException ex) {
             throw new IllegalArgumentException("amount must be numeric.");
         }
         ValidationUtils.requireAmountInRange(parsedAmount, "amount", config.getMaxAllowedAmount());
         String safeReason = ValidationUtils.requireValidReason(reason == null ? "" : reason.trim());
         String safeOfficer = officerId == null ? "" : officerId.trim();
         JsonObject payload = new JsonObject();
         payload.addProperty("vehicleId", safeVin);
         payload.addProperty("spaceId", safeSpace);
         payload.addProperty("amount", parsedAmount);
         payload.addProperty("reason", safeReason);
         if (!safeOfficer.isEmpty()) {
             payload.addProperty("officer", safeOfficer);
         }
         ValidationUtils.validateParkingPayload(payload.toString(), config.getMaxAllowedAmount(), "citation.issue");
         return payload.toString();
    }

    /**
     * Sanitizes a value for safe log output by replacing any character that is
     * not alphanumeric, dot, underscore, or hyphen with an underscore.
     *
     * @param value the raw value to sanitize; may be null
     * @return the sanitized string, or an empty string when the input is null
     */
    private static String safeForLog(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Immutable record of a single PEO activity (legality check, citation, or
     * parking event) used to populate the session's activity history view.
     */
    private static class PEOActivityLogEntry {
        final String action;
        final String vin;
        final String result;
        final long timestamp;

        /**
         * Creates an activity log entry capturing one PEO action.
         *
         * @param action    the action that was performed
         * @param vin       the vehicle identification number involved
         * @param result    the outcome or result text for the action
         * @param timestamp the time the action occurred, in epoch milliseconds
         */
        PEOActivityLogEntry(String action, String vin, String result, long timestamp) {
            this.action = action;
            this.vin = vin;
            this.result = result;
            this.timestamp = timestamp;
        }
    }

    /**
     * Extracts an event timestamp from a transaction document in epoch
     * milliseconds, handling numeric epoch seconds, ISO-8601 string timestamps,
     * and a numeric {@code storedAt} fallback.
     *
     * @param doc the transaction document to read the timestamp from
     * @return the timestamp in epoch milliseconds, or 0 if none could be parsed
     */
    private static long getTimestampMs(Document doc) {
        Object ts = doc.get("timestamp");
        if (ts instanceof Number) {
            return ((Number) ts).longValue() * 1000L;
        } else if (ts instanceof String) {
            try {
                return java.time.Instant.parse((String) ts).toEpochMilli();
            } catch (Exception e) {
                // ignore
            }
        }
        Object storedAt = doc.get("storedAt");
        if (storedAt instanceof Number) {
            return ((Number) storedAt).longValue();
        }
        return 0L;
    }
}
