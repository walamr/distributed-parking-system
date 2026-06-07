package edu.kinneret.parking.mo.cli;

import edu.kinneret.parking.common.*;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Scanner;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Command-Line Interface for the Municipality Office (MO) application.
 * Provides reports and statistics for administrators.
 * Satisfies the requirement to provide both GUIs and CLIs.
 */
public class MOCLI {
    /**
     * Default constructor for MOCLI.
     */
    public MOCLI() {
        // Default constructor
    }

    private static final Logger logger = LoggerFactory.getLogger(MOCLI.class);
    private static String loggedInManager = null;

    /**
     * Starts the cluster-aware MO command-line interface.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        // Suppress noisy background database connection logs from java.util.logging
        java.util.logging.Logger.getLogger("edu.kinneret.parking.common.MongoConnectionManager").setLevel(java.util.logging.Level.WARNING);

        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.MO_UI);
        RabbitMqConnectionManager rabbitManager = new RabbitMqConnectionManager(config);

        System.out.println("==========================================");
        System.out.println("  MULLIGAN PARKING - MUNICIPALITY CLUSTER CLI");
        System.out.println("==========================================");

        try (ParkingRepository repository = new ParkingRepository(config);
             Scanner scanner = new Scanner(System.in)) {
            while (true) {
                if (loggedInManager == null) {
                    System.out.println("\nStatus: NOT LOGGED IN");
                    System.out.println("Options: [1] Login, [2] Exit");
                    System.out.print("Select: ");
                    String choice = scanner.nextLine();

                    if ("2".equals(choice)) break;

                    if ("1".equals(choice)) {
                        System.out.print("Enter Manager ID (Username): ");
                        String managerId = scanner.nextLine().trim();
                        System.out.print("Enter Password: ");
                        String password = scanner.nextLine();

                        if (managerId.isEmpty()) {
                            System.out.println("ERROR: Manager ID cannot be empty.");
                            continue;
                        }

                        try {
                            repository.registerUser(managerId, password, "mo", "");
                            loggedInManager = managerId;
                            System.out.println("SUCCESS: Authenticated as manager '" + managerId + "'.");
                        } catch (Exception e) {
                            System.err.println("ERROR: Unable to connect to the database cluster for login. Please try again.");
                        }
                    } else {
                        System.out.println("Invalid choice.");
                    }
                } else {
                    System.out.println("\nStatus: LOGGED IN as Manager '" + loggedInManager + "'");
                    System.out.println("Options: [1] Transaction Report, [2] Citation Report, [3] Cluster Health, [4] List Registered Vehicles, [5] Logout, [6] Exit");
                    System.out.print("Select: ");
                    String choice = scanner.nextLine();

                    if ("6".equals(choice)) break;

                    switch (choice) {
                        case "1":
                            transactionsReport(repository);
                            break;
                        case "2":
                            citationsReport(repository);
                            break;
                        case "3":
                            clusterHealth(repository, rabbitManager);
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
                            System.out.println("Logging out manager '" + loggedInManager + "'.");
                            loggedInManager = null;
                            break;
                        default:
                            System.out.println("Invalid choice.");
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error in MO CLI: {}", SecurityLogger.sanitize(e.getMessage()));
            System.err.println("ERROR: Failed to initialize MO CLI. Please check configuration and try again.");
        }
        System.out.println("Exiting CLI.");
    }

    /**
     * Displays transaction report.
     *
     * @param repository the parking data repository
     */
    private static void transactionsReport(ParkingRepository repository) {
        try {
            List<Document> rawEvents = repository.getAllTransactions();
            
            // Consolidate start/stop events into sessions (similar to MOController)
            rawEvents.sort((d1, d2) -> {
                long t1 = d1.get("timestamp") != null ? ((Number) d1.get("timestamp")).longValue() : 0L;
                long t2 = d2.get("timestamp") != null ? ((Number) d2.get("timestamp")).longValue() : 0L;
                if (t1 != t2) {
                    return Long.compare(t1, t2);
                }
                String type1 = d1.getString("type");
                String type2 = d2.getString("type");
                boolean isStop1 = type1 != null && type1.endsWith(".stop");
                boolean isStop2 = type2 != null && type2.endsWith(".stop");
                if (isStop1 && !isStop2) {
                    return -1; // d1 (stop) comes first
                }
                if (!isStop1 && isStop2) {
                    return 1;  // d2 (stop) comes first
                }
                return 0;
            });
            
            Map<String, Document> activeSessions = new HashMap<>();
            List<Document> consolidated = new ArrayList<>();
            
            for (Document event : rawEvents) {
                String vehicleId = ParkingRepository.readPayloadField(event, "vehicleId");
                String spaceId = ParkingRepository.readPayloadField(event, "spaceId");
                String key = vehicleId + "_" + spaceId;
                String type = event.getString("type");
                
                if ("transaction.start".equals(type)) {
                    activeSessions.put(key, event);
                } else if ("transaction.stop".equals(type)) {
                    Document startEvent = activeSessions.remove(key);
                    Document session = new Document();
                    if (startEvent != null) {
                        session.put("vehicleId", vehicleId);
                        session.put("spaceId", spaceId);
                        String areaName = ParkingRepository.readPayloadField(event, "areaName");
                        if (areaName == null || areaName.isEmpty()) {
                            areaName = repository.getSpaceZone(spaceId);
                        }
                        session.put("areaName", areaName);
                        session.put("startTime", startEvent.getLong("timestamp"));
                        session.put("stopTime", event.getLong("timestamp"));
                        session.put("cost", ParkingRepository.readPayloadField(event, "cost"));
                        consolidated.add(session);
                    }
                }
            }
            
            for (Document startEvent : activeSessions.values()) {
                Document session = new Document();
                String spaceId = ParkingRepository.readPayloadField(startEvent, "spaceId");
                session.put("vehicleId", ParkingRepository.readPayloadField(startEvent, "vehicleId"));
                session.put("spaceId", spaceId);
                String areaName = ParkingRepository.readPayloadField(startEvent, "areaName");
                if (areaName == null || areaName.isEmpty()) {
                    areaName = repository.getSpaceZone(spaceId);
                }
                session.put("areaName", areaName);
                session.put("startTime", startEvent.getLong("timestamp"));
                session.put("stopTime", null);
                session.put("cost", null);
                consolidated.add(session);
            }
            
            consolidated.sort((d1, d2) -> {
                Long t1 = d1.getLong("startTime");
                Long t2 = d2.getLong("startTime");
                if (t1 == null) t1 = 0L;
                if (t2 == null) t2 = 0L;
                return Long.compare(t2, t1);
            });

            System.out.println("\n========== TRANSACTION REPORT ==========");
            System.out.println("Total Transactions: " + consolidated.size());
            System.out.println("\nDetails:");
            System.out.println(String.format("%-10s %-10s %-20s %-20s %-20s %-8s", "VIN", "SPACE", "ZONE", "START TIME", "STOP TIME", "COST"));
            System.out.println("-".repeat(98));
            
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            
            for (Document session : consolidated) {
                String vin = session.getString("vehicleId");
                String space = session.getString("spaceId");
                String zone = session.getString("areaName");
                
                Long startTimeVal = session.getLong("startTime");
                String startTimeStr = "N/A";
                if (startTimeVal != null) {
                    LocalDateTime date = LocalDateTime.ofEpochSecond(startTimeVal, 0, ZoneOffset.UTC);
                    startTimeStr = date.format(formatter);
                }
                
                Long stopTimeVal = session.getLong("stopTime");
                String stopTimeStr = "N/A";
                if (stopTimeVal != null) {
                    LocalDateTime date = LocalDateTime.ofEpochSecond(stopTimeVal, 0, ZoneOffset.UTC);
                    stopTimeStr = date.format(formatter);
                }
                
                String cost = session.getString("cost");
                if (cost == null || cost.isEmpty() || cost.equals("null")) {
                    cost = "N/A";
                }
                
                System.out.println(String.format("%-10s %-10s %-20s %-20s %-20s %-8s", 
                    vin != null ? vin : "N/A",
                    space != null ? space : "N/A",
                    zone != null ? zone : "N/A",
                    startTimeStr,
                    stopTimeStr,
                    cost));
            }
            System.out.println("========================================");
        } catch (Exception e) {
            logger.warn("Failed to retrieve transactions: {}", SecurityLogger.sanitize(e.getMessage()));
            System.err.println("ERROR: Unable to retrieve transactions. Please try again later.");
        }
    }

    /**
     * Displays citation report.
     *
     * @param repository the parking data repository
     */
    private static void citationsReport(ParkingRepository repository) {
        try {
            List<Document> citations = repository.getAllCitations();
            System.out.println("\n========== CITATION REPORT ==========");
            System.out.println("Total Citations: " + citations.size());
            System.out.println("\nDetails:");
            System.out.println(String.format("%-10s %-10s %-8s %-15s %-30s %-20s", "VIN", "SPACE", "AMOUNT", "OFFICER", "REASON", "ISSUED TIME"));
            System.out.println("-".repeat(99));
            
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            
            for (Document citation : citations) {
                String vin = ParkingRepository.readPayloadField(citation, "vehicleId");
                String space = ParkingRepository.readPayloadField(citation, "spaceId");
                String amount = ParkingRepository.readPayloadField(citation, "amount");
                String officer = ParkingRepository.readPayloadField(citation, "officer");
                if (officer == null || officer.isEmpty() || officer.equals("null")) {
                    officer = "N/A";
                }
                String reason = ParkingRepository.readPayloadField(citation, "reason");
                
                Long epoch = citation.getLong("timestamp");
                String issuedTime = "N/A";
                if (epoch != null) {
                    LocalDateTime date = LocalDateTime.ofEpochSecond(epoch, 0, ZoneOffset.UTC);
                    issuedTime = date.format(formatter);
                }
                
                System.out.println(String.format("%-10s %-10s %-8s %-15s %-30s %-20s",
                    vin != null ? vin : "N/A",
                    space != null ? space : "N/A",
                    amount != null ? amount : "N/A",
                    officer,
                    reason != null ? reason : "N/A",
                    issuedTime));
            }
            System.out.println("=====================================");
        } catch (Exception e) {
            logger.warn("Failed to retrieve citations: {}", SecurityLogger.sanitize(e.getMessage()));
            System.err.println("ERROR: Unable to retrieve citations. Please try again later.");
        }
    }

    /**
     * Displays cluster health status.
     *
     * @param repository the parking data repository
     * @param rabbitManager the RabbitMQ connection manager
     */
    private static void clusterHealth(ParkingRepository repository, RabbitMqConnectionManager rabbitManager) {
        try {
            System.out.println("\n========== CLUSTER HEALTH STATUS ==========");
            
            // MongoDB status
            System.out.println("\nMongoDB Replica Set Status:");
            Document mongoStatus = repository.getClusterStatus();
            if (mongoStatus != null) {
                Object ok = mongoStatus.get("ok");
                List<?> members = (List<?>) mongoStatus.get("members");
                System.out.println("  Overall Status: " + ok);
                System.out.println("  Members:");
                if (members != null) {
                    for (Object m : members) {
                        if (m instanceof Document d) {
                            String name = d.getString("name");
                            String state = d.getString("stateStr");
                            Object health = d.get("health");
                            int healthVal = health instanceof Number n ? n.intValue() : 0;
                            String healthStr = healthVal == 1 ? "HEALTHY" : "UNHEALTHY";
                            System.out.println(String.format("    - %s: %s (%s)", name, state, healthStr));
                        }
                    }
                }
            } else {
                System.out.println("  ERROR: Unable to retrieve MongoDB status");
            }
            
            // RabbitMQ status
            System.out.println("\nRabbitMQ Cluster Status:");
            boolean rabbitHealthy = rabbitManager.checkHealth();
            System.out.println("  Overall Status: " + (rabbitHealthy ? "HEALTHY" : "UNHEALTHY"));
            
            System.out.println("==========================================");
        } catch (Exception e) {
            logger.warn("Failed to retrieve cluster health: {}", SecurityLogger.sanitize(e.getMessage()));
            System.err.println("ERROR: Unable to retrieve cluster health. Please try again later.");
        }
    }
}
