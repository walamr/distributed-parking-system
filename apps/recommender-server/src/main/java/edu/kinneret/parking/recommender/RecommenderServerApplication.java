package edu.kinneret.parking.recommender;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.ParkingRepository;
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
 * Supports running in either JavaFX GUI mode (default) or CLI mode (via --cli flag).
 */
public class RecommenderServerApplication extends Application {
    private static RecommenderServer server;
    private static String nodeId = "recommender1";
    private static int port = 8091;
    private static String leaderHost = "localhost";
    private static int leaderPort = 8091;
    private static boolean isLeader = true;
    private static boolean isMalicious = false;
    private static List<String> clusterNodes = Arrays.asList("localhost:8091", "localhost:8092", "localhost:8093");
    private static boolean runCli = false;

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
                String[] parts = arg.split("=");
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
        isLeader = Boolean.getBoolean("isLeader") || isLeader;
        isMalicious = Boolean.getBoolean("malicious") || isMalicious;
        if (System.getProperty("nodes") != null) {
            clusterNodes = Arrays.asList(System.getProperty("nodes").split(","));
        }

        AppConfig appConfig = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.CUSTOMER_UI);

        try {
            server = new RecommenderServer(nodeId, port, leaderHost, leaderPort, isLeader, isMalicious, clusterNodes, appConfig);
            server.start();
        } catch (IOException e) {
            System.err.println("Fatal: Could not start recommender server: " + e.getMessage());
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

        statusLabel = new Label();
        updateStatusText();

        root.getChildren().addAll(titleLabel, grid, maliciousCheck, statusLabel);

        Scene scene = new Scene(root, 400, 300);
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> {
            server.close();
            Platform.exit();
            System.exit(0);
        });
        primaryStage.show();
    }

    private void addGridRow(GridPane grid, int row, String label, String value) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-text-fill: #a6adc8; -fx-font-weight: bold;");
        Label val = new Label(value);
        val.setStyle("-fx-text-fill: #cdd6f4;");
        grid.add(lbl, 0, row);
        grid.add(val, 1, row);
    }

    private void updateStatusText() {
        if (server.isMalicious()) {
            statusLabel.setText("Status: ACTIVE - RUNNING IN MALICIOUS MODE (Returns faked space)");
            statusLabel.setStyle("-fx-text-fill: #f38ba8; -fx-font-weight: bold;");
        } else {
            statusLabel.setText("Status: ACTIVE - RUNNING IN NORMAL MODE");
            statusLabel.setStyle("-fx-text-fill: #a6e3a1; -fx-font-weight: bold;");
        }
    }

    private static void runConsoleMenu() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("==========================================");
        System.out.println("   RECOMMENDER NODE '" + nodeId + "' CLI");
        System.out.println("==========================================");
        System.out.println("Listen Port: " + port);
        System.out.println("Leader status: " + isLeader);

        while (true) {
            System.out.println("\nNode Mode: " + (server.isMalicious() ? "MALICIOUS" : "NORMAL"));
            System.out.println("Options: [m] Toggle Malicious Mode, [q] Quit Node");
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
            } else {
                System.out.println("Invalid option.");
            }
        }
        System.out.println("CLI terminated.");
        System.exit(0);
    }
}
