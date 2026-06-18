package edu.kinneret.parking.recommender;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.SecurityLogger;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.*;

/**
 * Main application launcher for the Recommender Server.
 * Supports running in either JavaFX GUI mode (default) or CLI mode (via --cli
 * flag).
 */
public class RecommenderServerApplication extends Application {
    private static RecommenderServer server;
    private static String nodeId = "recommender1";
    private static int port = 8091;
    private static String leaderHost = "localhost";
    private static int leaderPort = 8091;
    private static boolean isLeader = true;
    private static boolean isMalicious = false;
    private static String maliciousPayload = null;
    private static List<String> clusterNodes = Arrays.asList("localhost:8091", "localhost:8092", "localhost:8093");
    private static boolean runCli = false;

    private static Label latestRequestLabel;
    private static Label latestResultLabel;
    private static Label latestVehicleLabel;

    private CheckBox maliciousCheck;
    private Label statusLabel;

    /**
     * Main entry point for the recommender application.
     *
     * @param args command-line arguments:
     *             --cli (to run in console CLI mode)
     *             nodeId=...
     *             port=...
     *             leaderHost=...
     *             leaderPort=...
     *             isLeader=true/false
     *             malicious=true/false
     *             nodes=host1:port1,host2:port2,...
     */
    public static void main(String[] args) {
        // Parse arguments
        for (String arg : args) {
            if (arg.equals("--cli")) {
                runCli = true;
            } else if (arg.contains("=")) {
                String[] parts = arg.split("=", 2);
                String key = parts[0].trim();
                String value = parts[1].trim();
                switch (key) {
                    case "nodeId":
                        nodeId = value;
                        break;
                    case "port":
                        port = Integer.parseInt(value);
                        break;
                    case "leaderHost":
                        leaderHost = value;
                        break;
                    case "leaderPort":
                        leaderPort = Integer.parseInt(value);
                        break;
                    case "isLeader":
                        isLeader = Boolean.parseBoolean(value);
                        break;
                    case "malicious":
                        isMalicious = Boolean.parseBoolean(value);
                        break;
                    case "maliciousPayload":
                        maliciousPayload = value;
                        break;
                    case "nodes":
                        clusterNodes = Arrays.asList(value.split(","));
                        break;
                }
            }
        }

        // Apply fallback system properties if passed
        nodeId = System.getProperty("nodeId", nodeId);
        port = Integer.getInteger("port", port);
        leaderHost = System.getProperty("leaderHost", leaderHost);
        leaderPort = Integer.getInteger("leaderPort", leaderPort);
        
        if (System.getProperty("isLeader") != null) {
            isLeader = Boolean.parseBoolean(System.getProperty("isLeader"));
        }
        if (System.getProperty("malicious") != null) {
            isMalicious = Boolean.parseBoolean(System.getProperty("malicious"));
        }
        if (System.getProperty("cli") != null) {
            runCli = Boolean.parseBoolean(System.getProperty("cli"));
        }
        
        maliciousPayload = System.getProperty("maliciousPayload", maliciousPayload);
        if (System.getProperty("nodes") != null) {
            clusterNodes = Arrays.asList(System.getProperty("nodes").split(","));
        }

        SecurityLogger.initialize(System.getenv().getOrDefault("SECURITY_LOG_PATH", "logs/recommender-security.log"));
        AppConfig appConfig = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.QUEUE_SERVER);

        // Fallback to AppConfig for recommender nodes if not explicitly configured in system property or CLI arguments
        boolean hasNodesOverride = false;
        for (String arg : args) {
            if (arg.contains("nodes=")) {
                hasNodesOverride = true;
                break;
            }
        }
        if (System.getProperty("nodes") != null) {
            hasNodesOverride = true;
        }

        if (!hasNodesOverride && appConfig.getRecommenderNodes() != null && !appConfig.getRecommenderNodes().isEmpty()) {
            List<String> nodesFromConfig = new ArrayList<>();
            for (edu.kinneret.parking.common.ClusterNode node : appConfig.getRecommenderNodes()) {
                nodesFromConfig.add(node.getDisplayName() + "=" + node.getHost() + ":" + node.getPort());
            }
            clusterNodes = nodesFromConfig;
        }

        // Fallback for leaderHost/leaderPort if not explicitly configured
        boolean hasLeaderOverride = false;
        for (String arg : args) {
            if (arg.contains("leaderHost=") || arg.contains("leaderPort=")) {
                hasLeaderOverride = true;
                break;
            }
        }
        if (System.getProperty("leaderHost") != null || System.getProperty("leaderPort") != null) {
            hasLeaderOverride = true;
        }

        if (!hasLeaderOverride && appConfig.getRecommenderNodes() != null && !appConfig.getRecommenderNodes().isEmpty()) {
            // Find recommender1 if exists, otherwise first node
            edu.kinneret.parking.common.ClusterNode leaderNode = appConfig.getRecommenderNodes().get(0);
            for (edu.kinneret.parking.common.ClusterNode node : appConfig.getRecommenderNodes()) {
                if ("recommender1".equals(node.getDisplayName())) {
                    leaderNode = node;
                    break;
                }
            }
            leaderHost = leaderNode.getHost();
            leaderPort = leaderNode.getPort();
        }

        System.out.println("=================================================================");
        System.out.println("Recommender Server Startup Parameters:");
        System.out.println("  - Node ID: " + nodeId);
        System.out.println("  - Listen Port: " + port);
        System.out.println("  - Is Leader: " + isLeader);
        System.out.println("  - Leader Address: " + leaderHost + ":" + leaderPort);
        System.out.println("  - Cluster Nodes: " + clusterNodes);
        System.out.println("=================================================================");

        try {
            server = new RecommenderServer(nodeId, port, leaderHost, leaderPort, isLeader, isMalicious, clusterNodes,
                    appConfig);
            if (maliciousPayload != null) {
                server.setMaliciousPayload(maliciousPayload);
            }
            server.start();
        } catch (IOException e) {
            System.err.println("Fatal: Could not start recommender server. Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        if (runCli) {
            runConsoleMenu();
        } else {
            launch(args);
        }
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Mulligan Recommender Node: " + nodeId);

        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: #1e1e2e; -fx-text-fill: white;");

        Label titleLabel = new Label("Recommender Node Control Panel");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #cdd6f4;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setAlignment(Pos.CENTER);

        addGridRow(grid, 0, "Node ID:", nodeId);
        addGridRow(grid, 1, "Listen Port:", String.valueOf(port));
        addGridRow(grid, 2, "Leader Status:", isLeader ? "LEADER (Coordinator)" : "FOLLOWER");
        addGridRow(grid, 3, "Leader Address:", leaderHost + ":" + leaderPort);

        maliciousCheck = new CheckBox("Enable Malicious Mode");
        maliciousCheck.setSelected(isMalicious);
        maliciousCheck.setStyle("-fx-text-fill: #f38ba8; -fx-font-weight: bold; -fx-font-size: 14px;");
        maliciousCheck.setOnAction(e -> {
            boolean selected = maliciousCheck.isSelected();
            server.setMalicious(selected);
            updateStatusText();
        });

        javafx.scene.layout.HBox payloadBox = new javafx.scene.layout.HBox(10);
        payloadBox.setAlignment(Pos.CENTER);
        Label payloadLabel = new Label("Malicious Payload:");
        payloadLabel.setStyle("-fx-text-fill: #a6adc8; -fx-font-weight: bold;");
        TextField payloadField = new TextField(server.getMaliciousPayload());
        payloadField.setPrefWidth(150);
        payloadField.setStyle("-fx-background-color: #313244; -fx-text-fill: #cdd6f4; -fx-prompt-text-fill: #585b70;");
        payloadField.textProperty().addListener((obs, oldVal, newVal) -> {
            try {
                server.setMaliciousPayload(newVal);
                payloadField.setStyle(
                        "-fx-background-color: #313244; -fx-text-fill: #cdd6f4; -fx-prompt-text-fill: #585b70;");
                updateStatusText();
            } catch (IllegalArgumentException ex) {
                payloadField.setStyle(
                        "-fx-background-color: #313244; -fx-text-fill: #f38ba8; -fx-prompt-text-fill: #585b70; -fx-border-color: #f38ba8; -fx-border-width: 1px;");
                statusLabel.setText("Error: Invalid payload format!");
                statusLabel.setStyle("-fx-text-fill: #f38ba8; -fx-font-weight: bold;");
            }
        });
        payloadBox.getChildren().addAll(payloadLabel, payloadField);

        statusLabel = new Label();
        updateStatusText();

        Separator separator = new Separator();
        separator.setStyle("-fx-background-color: #313244;");

        Label recTitleLabel = new Label("Latest Recommendation");
        recTitleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #a6e3a1;");

        GridPane recGrid = new GridPane();
        recGrid.setHgap(10);
        recGrid.setVgap(10);
        recGrid.setAlignment(Pos.CENTER);

        latestRequestLabel = new Label("-");
        latestRequestLabel.setStyle("-fx-text-fill: #f9e2af; -fx-font-weight: bold;");
        latestResultLabel = new Label("-");
        latestResultLabel.setStyle("-fx-text-fill: #a6e3a1; -fx-font-weight: bold;");
        latestVehicleLabel = new Label("-");
        latestVehicleLabel.setStyle("-fx-text-fill: #89b4fa; -fx-font-weight: bold;");

        addGridRowWithLabel(recGrid, 0, "Requested Space:", latestRequestLabel);
        addGridRowWithLabel(recGrid, 1, "Vehicle ID:", latestVehicleLabel);
        addGridRowWithLabel(recGrid, 2, "Recommended Space:", latestResultLabel);

        root.getChildren().addAll(titleLabel, grid, maliciousCheck, payloadBox, statusLabel, separator, recTitleLabel, recGrid);

        Scene scene = new Scene(root, 400, 450);
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> {
            server.close();
            Platform.exit();
            System.exit(0);
        });
        primaryStage.show();
    }

    private void addGridRowWithLabel(GridPane grid, int row, String label, Label valLabel) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-text-fill: #a6adc8; -fx-font-weight: bold;");
        grid.add(lbl, 0, row);
        grid.add(valLabel, 1, row);
    }

    public static void updateLatestQuery(String requestedSpace, String vehicleId, String recommendedSpace) {
        if (latestRequestLabel != null && latestResultLabel != null && latestVehicleLabel != null) {
            Platform.runLater(() -> {
                latestRequestLabel.setText("Space " + requestedSpace);
                latestVehicleLabel.setText(vehicleId != null ? vehicleId : "-");
                if (recommendedSpace == null || recommendedSpace.isBlank()) {
                    latestResultLabel.setText("None");
                } else {
                    String clean = recommendedSpace.replace("Result:", "").replace("Space ", "").trim();
                    latestResultLabel.setText(clean);
                }
            });
        }
    }

    public static void updateLatestQuery(String requestedSpace, String recommendedSpace) {
        updateLatestQuery(requestedSpace, "-", recommendedSpace);
    }

    /**
     * Adds a labelled read-only row to the status grid pane.
     *
     * @param grid  the target grid pane
     * @param row   the zero-based row index
     * @param label the left-column label text
     * @param value the right-column value text
     */
    private void addGridRow(GridPane grid, int row, String label, String value) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-text-fill: #a6adc8; -fx-font-weight: bold;");
        Label val = new Label(value);
        val.setStyle("-fx-text-fill: #cdd6f4;");
        grid.add(lbl, 0, row);
        grid.add(val, 1, row);
    }

    /**
     * Refreshes the status label to reflect whether malicious mode is active.
     */
    private void updateStatusText() {
        if (server.isMalicious()) {
            statusLabel.setText("Status: ACTIVE - MALICIOUS MODE (" + server.getMaliciousPayload() + ")");
            statusLabel.setStyle("-fx-text-fill: #f38ba8; -fx-font-weight: bold;");
        } else {
            statusLabel.setText("Status: ACTIVE - RUNNING IN NORMAL MODE");
            statusLabel.setStyle("-fx-text-fill: #a6e3a1; -fx-font-weight: bold;");
        }
    }

    /**
     * Runs an interactive command-line menu that lets an operator toggle malicious
     * mode and change the malicious payload at runtime.
     */
    private static void runConsoleMenu() {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("==========================================");
            System.out.println("   RECOMMENDER NODE '" + nodeId + "' CLI");
            System.out.println("==========================================");
            System.out.println("Listen Port: " + port);
            System.out.println("Leader status: " + isLeader);

            while (true) {
                System.out.println("\nNode Mode: " + (server.isMalicious() ? "MALICIOUS" : "NORMAL"));
                System.out.println("Malicious Payload: " + server.getMaliciousPayload());
                System.out.println("Options: [m] Toggle Malicious Mode, [p] Change Malicious Payload, [q] Quit Node");
                System.out.print("Select: ");

                if (!scanner.hasNextLine()) {
                    // Keep the process alive in non-interactive environment (e.g. Docker)
                    while (true) {
                        try {
                            Thread.sleep(3600000);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    break;
                }

                String input = scanner.nextLine().trim().toLowerCase();

                if ("q".equals(input)) {
                    System.out.println("Stopping Recommender Node...");
                    server.close();
                    break;
                } else if ("m".equals(input)) {
                    boolean nextState = !server.isMalicious();
                    server.setMalicious(nextState);
                    System.out.println("SUCCESS: Toggled malicious mode to: " + nextState);
                } else if ("p".equals(input)) {
                    System.out.print("Enter the fake Lot ID (e.g. 5): ");
                    if (scanner.hasNextLine()) {
                        String fakeLot = scanner.nextLine().trim();
                        System.out.print("Enter the fake Space ID (e.g. 1): ");
                        if (scanner.hasNextLine()) {
                            String fakeSpace = scanner.nextLine().trim();
                            String newPayload = fakeLot + ";" + fakeSpace;
                            try {
                                server.setMaliciousPayload(newPayload);
                                System.out.println("SUCCESS: Changed malicious payload to: " + server.getMaliciousPayload());
                            } catch (IllegalArgumentException ex) {
                                System.out.println("ERROR: " + ex.getMessage());
                            }
                        }
                    }
                } else {
                    System.out.println("Invalid option.");
                }
            }
        }
        System.out.println("CLI terminated.");
        System.exit(0);
    }
}
