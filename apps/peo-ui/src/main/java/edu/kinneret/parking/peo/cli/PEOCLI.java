package edu.kinneret.parking.peo.cli;

import edu.kinneret.parking.common.*;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
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
                            repository.registerUser(officerId, password, "peo", "");
                            loggedInOfficer = officerId;
                            System.out.println("SUCCESS: Authenticated as officer '" + officerId + "'.");
                        } catch (Exception e) {
                            System.err.println("ERROR: Unable to connect to the database cluster for login. Please try again.");
                        }
                    } else {
                        System.out.println("Invalid choice.");
                    }
                } else {
                    System.out.println("\nStatus: LOGGED IN as Officer '" + loggedInOfficer + "'");
                    System.out.println("Options: [1] Check Legality, [2] Issue Citation, [3] List Registered Vehicles, [4] Logout, [5] Exit");
                    System.out.print("Select: ");
                    String choice = scanner.nextLine();

                    if ("5".equals(choice)) break;

                    switch (choice) {
                        case "1":
                            System.out.print("Enter Vehicle VIN: ");
                            String checkVin = scanner.nextLine();
                            System.out.print("Enter Space ID: ");
                            String checkSpaceId = scanner.nextLine();
                            try {
                                String result = repository.checkLegality(checkVin, checkSpaceId);
                                System.out.println("LEGALITY CHECK: " + result);
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
                        case "4":
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

 * Issue citation.

 * @param manager the manager

 * @param config the config

 * @param signer the signer

 * @param vin the vin

 * @param spaceId the spaceId

 * @param amount the amount

 * @param reason the reason

 * @param officerId the officerId

 */

     private static void issueCitation(RabbitMqConnectionManager manager, AppConfig config, SecureMessageSigner signer, String vin, String spaceId, String amount, String reason, String officerId) {
         try {
             String payload = buildCitationPayload(vin, spaceId, amount, reason, officerId, config);
             String correlationId = UUID.randomUUID().toString();
             String clientIp = InetAddress.getLocalHost().getHostAddress();
             MessageEnvelope envelope = MessageEnvelope.createUnsigned("citation.issue", payload, clientIp, correlationId).sign(signer);
             
             manager.withChannelForQueue(config.getCitationsQueueName(), (channel, node) -> {
                 channel.basicPublish("", config.getCitationsQueueName(), null, envelope.toJsonString().getBytes(StandardCharsets.UTF_8));
                 System.out.println("SUCCESS: Citation published via cluster node: " + node.toAddress());
             });
         } catch (Exception e) {
             logger.warn("Failed to publish citation request to the cluster: {}", SecurityLogger.sanitize(e.getMessage()));
             System.err.println("ERROR: Request could not be processed. Please try again.");
         }
     }
 
/**
 
 * Build citation payload.
 
 * @param vin the vin
 
 * @param spaceId the spaceId
 
 * @param amount the amount
 
 * @param reason the reason
 
 * @param config the config
 
 * @return the string
 
 */
 
     static String buildCitationPayload(String vin, String spaceId, String amount, String reason, AppConfig config) {
         /**
          * Build citation payload.
          * @param vin the vin
          * @param spaceId the spaceId
          * @param amount the amount
          * @param reason the reason
          * @param config the config
          * @return the return
          */
         return buildCitationPayload(vin, spaceId, amount, reason, "", config);
     }

/**

 * Build citation payload.

 * @param vin the vin

 * @param spaceId the spaceId

 * @param amount the amount

 * @param reason the reason

 * @param officerId the officerId

 * @param config the config

 * @return the string

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

 * Safe for log.

 * @param value the value

 * @return the string

 */

    private static String safeForLog(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
