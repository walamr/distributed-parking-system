package edu.kinneret.parking.mo.ui;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.ParkingRepository;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main application class for the Municipality Office (MO) UI.
 * Provides reports and statistics for administrators.
 */
public class MOApp extends Application {
    
    /**
     * Default constructor for MOApp.
     */
    public MOApp() {
    }
    
    static {
        System.setProperty("glass.win.uiScale", "1.0");
        System.setProperty("prism.allowhidpi", "false");
    }

    private static final Logger logger = LoggerFactory.getLogger(MOApp.class);

    /**
     * Initializes the JavaFX application and sets up the primary stage.
     * @param primaryStage the main window for the MO UI
     */
    @Override
    public void start(Stage primaryStage) {
        logger.info("Starting Cluster-Aware MO UI...");
        primaryStage.setTitle("Mulligan Parking System - Municipality Cluster UI");
        primaryStage.setResizable(false);
        primaryStage.setMinWidth(1100);
        primaryStage.setMinHeight(850);
        primaryStage.setMaxWidth(1100);
        primaryStage.setMaxHeight(850);
        primaryStage.maximizedProperty().addListener((obs, oldVal, newValue) -> {
            if (newValue) {
                primaryStage.setMaximized(false);
            }
        });
        
        Parent root = createContent();
        Scene scene = new Scene(root, 1100, 850);
        try {
            scene.getStylesheets().add(getClass().getResource("/style-mo.css").toExternalForm());
        } catch (Exception e) {}

        primaryStage.setScene(scene);
        primaryStage.show();
    }

    /**
     * Creates the main layout and initializes the MO controller.
     * @return the root parent node for the scene
     */
    @SuppressWarnings("unchecked")
    public Parent createContent() {
        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.MO_UI);
        ParkingRepository repository = new ParkingRepository(config);
        
        MOController controller = new MOController(repository, config);

        Button transactionsButton = new Button("📄  GET TRANSACTION REPORT");
        Button citationsButton = new Button("🔨  GET CITATION REPORT");

        TableView<org.bson.Document> transactionsTable = new TableView<>();
        transactionsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        
        TableColumn<org.bson.Document, String> txVinCol = new TableColumn<>("Vehicle (VIN)");
        txVinCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().getString("vehicleId")));
                
        TableColumn<org.bson.Document, String> txSpaceCol = new TableColumn<>("Space");
        txSpaceCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().getString("spaceId")));

        TableColumn<org.bson.Document, String> txZoneCol = new TableColumn<>("Zone");
        txZoneCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().getString("areaName")));

        TableColumn<org.bson.Document, String> txDateCol = new TableColumn<>("Date");
        txDateCol.setCellValueFactory(d -> {
            try {
                long epoch = d.getValue().getLong("startTime");
                java.time.LocalDateTime date = java.time.LocalDateTime.ofEpochSecond(epoch, 0, java.time.ZoneOffset.UTC);
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
                return new javafx.beans.property.SimpleStringProperty(date.format(formatter));
            } catch (Exception e) { return new javafx.beans.property.SimpleStringProperty("-"); }
        });

        TableColumn<org.bson.Document, String> txStartCol = new TableColumn<>("Start Time");
        txStartCol.setCellValueFactory(d -> {
            try {
                long epoch = d.getValue().getLong("startTime");
                java.time.LocalDateTime date = java.time.LocalDateTime.ofEpochSecond(epoch, 0, java.time.ZoneOffset.UTC);
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
                return new javafx.beans.property.SimpleStringProperty(date.format(formatter));
            } catch (Exception e) { return new javafx.beans.property.SimpleStringProperty("-"); }
        });

        TableColumn<org.bson.Document, String> txStopCol = new TableColumn<>("Stop Time");
        txStopCol.setCellValueFactory(d -> {
            if (d.getValue().get("stopTime") == null) return new javafx.beans.property.SimpleStringProperty("-");
            try {
                long epoch = d.getValue().getLong("stopTime");
                java.time.LocalDateTime date = java.time.LocalDateTime.ofEpochSecond(epoch, 0, java.time.ZoneOffset.UTC);
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
                return new javafx.beans.property.SimpleStringProperty(date.format(formatter));
            } catch (Exception e) { return new javafx.beans.property.SimpleStringProperty("-"); }
        });
        
        TableColumn<org.bson.Document, String> txCostCol = new TableColumn<>("Amount (NIS)");
        txCostCol.setCellValueFactory(d -> {
            String cost = d.getValue().getString("cost");
            if (cost == null || cost.isEmpty() || cost.equals("null")) {
                return new javafx.beans.property.SimpleStringProperty("-");
            }
            return new javafx.beans.property.SimpleStringProperty(cost);
        });

        transactionsTable.getColumns().addAll(txVinCol, txSpaceCol, txZoneCol, txDateCol, txStartCol, txStopCol, txCostCol);

        TableView<org.bson.Document> citationsTable = new TableView<>();
        citationsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        
        TableColumn<org.bson.Document, String> ctVinCol = new TableColumn<>("Vehicle (VIN)");
        ctVinCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                ParkingRepository.readPayloadField(d.getValue(), "vehicleId")));
                
        TableColumn<org.bson.Document, String> ctSpaceCol = new TableColumn<>("Space");
        ctSpaceCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                ParkingRepository.readPayloadField(d.getValue(), "spaceId")));

        TableColumn<org.bson.Document, String> ctZoneCol = new TableColumn<>("Zone");
        ctZoneCol.setCellValueFactory(d -> {
            String zone = ParkingRepository.readPayloadField(d.getValue(), "areaName");
            return new javafx.beans.property.SimpleStringProperty(zone != null ? zone : "-");
        });

        TableColumn<org.bson.Document, String> ctDateCol = new TableColumn<>("Date");
        ctDateCol.setCellValueFactory(d -> {
            try {
                long epoch = d.getValue().getLong("timestamp");
                java.time.LocalDateTime date = java.time.LocalDateTime.ofEpochSecond(epoch, 0, java.time.ZoneOffset.UTC);
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
                return new javafx.beans.property.SimpleStringProperty(date.format(formatter));
            } catch (Exception e) { return new javafx.beans.property.SimpleStringProperty("-"); }
        });

        TableColumn<org.bson.Document, String> ctTimeCol = new TableColumn<>("Inspection Time");
        ctTimeCol.setCellValueFactory(d -> {
            try {
                long epoch = d.getValue().getLong("timestamp");
                java.time.LocalDateTime date = java.time.LocalDateTime.ofEpochSecond(epoch, 0, java.time.ZoneOffset.UTC);
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
                return new javafx.beans.property.SimpleStringProperty(date.format(formatter));
            } catch (Exception e) { return new javafx.beans.property.SimpleStringProperty("-"); }
        });

        TableColumn<org.bson.Document, String> ctOfficerCol = new TableColumn<>("PEO (Officer ID)");
        ctOfficerCol.setCellValueFactory(d -> {
            String officer = ParkingRepository.readPayloadField(d.getValue(), "officer");
            return new javafx.beans.property.SimpleStringProperty(officer != null && !officer.isEmpty() ? officer : "-");
        });

        TableColumn<org.bson.Document, String> ctReasonCol = new TableColumn<>("Reason");
        ctReasonCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                ParkingRepository.readPayloadField(d.getValue(), "reason")));
                
        TableColumn<org.bson.Document, String> ctFineCol = new TableColumn<>("Fine (NIS)");
        ctFineCol.setCellValueFactory(d -> {
            String amount = ParkingRepository.readPayloadField(d.getValue(), "amount");
            return new javafx.beans.property.SimpleStringProperty(amount != null ? amount : "-");
        });

        citationsTable.getColumns().addAll(ctVinCol, ctSpaceCol, ctZoneCol, ctOfficerCol, ctDateCol, ctTimeCol, ctReasonCol, ctFineCol);

        Label statusLabel = new Label("");
        Label tableHeader = new Label("REPORT OUTPUT");
        
        // --- CLUSTER HEALTH DASHBOARD (Hardening R2-Visibility) ---
        // Labels will be updated by the controller's background thread
        Label mongoStatus = new Label("⌛ Connecting...");
        Label rabbitStatus = new Label("⌛ Connecting...");

        VBox healthDashboard = createHealthDashboard(mongoStatus, rabbitStatus);
        
        Label nodeInfo = new Label("📡 Active Database Cluster Connection: " + config.getMongoUri());
        nodeInfo.getStyleClass().add("status-label");
        nodeInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");

        controller.attach(transactionsTable, citationsTable, statusLabel, tableHeader);
        controller.attachHealth(mongoStatus, rabbitStatus); // --- NEW: Start health monitor ---
        controller.attachButtons(transactionsButton, citationsButton);

        VBox headerTitles = new VBox(10, new Label("MUNICIPALITY DASHBOARD"), new Label("Distributed Cluster Architecture"));
        
        Button logoutBtn = new Button("🚪 Logout");
        logoutBtn.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 10 20; -fx-cursor: hand; -fx-background-radius: 8;");
        logoutBtn.setOnAction(e -> {
            if (edu.kinneret.parking.common.ui.LoginController.onLogout != null) {
                edu.kinneret.parking.common.ui.LoginController.onLogout.run();
            }
        });

        HBox headerBox = new HBox(headerTitles, logoutBtn);
        HBox.setHgrow(headerTitles, Priority.ALWAYS);
        headerBox.setAlignment(Pos.CENTER_LEFT);
        headerBox.setPadding(new Insets(20));
        headerBox.getStyleClass().add("header-container");

        HBox actionsRow = new HBox(15, transactionsButton, citationsButton);
        actionsRow.setPadding(new Insets(20));
        
        VBox topSection = new VBox(headerBox, actionsRow, healthDashboard, nodeInfo);
        topSection.setPadding(new Insets(0, 0, 0, 20));

        VBox outputContainer = new VBox(10, tableHeader, transactionsTable, citationsTable, statusLabel);
        VBox.setVgrow(outputContainer, Priority.ALWAYS);
        outputContainer.setPadding(new Insets(20));
        outputContainer.getStyleClass().add("glass-pane");

        VBox rootVBox = new VBox(topSection, outputContainer);
        
        StackPane root = new StackPane();
        try {
            Image bgImage = new Image(getClass().getResourceAsStream("/background.png"));
            ImageView bgView = new ImageView(bgImage);
            bgView.setFitWidth(1100);
            bgView.setFitHeight(850);
            root.getChildren().add(bgView);
        } catch (Exception e) {}

        root.getChildren().add(rootVBox);
        return root;
    }

    /**
     * Builds the real-time cluster health dashboard panel showing MongoDB and RabbitMQ status.
     *
     * @param mongoStatus  the label updated with the MongoDB replica set status
     * @param rabbitStatus the label updated with the RabbitMQ cluster status
     * @return the assembled dashboard container
     */
    private VBox createHealthDashboard(Label mongoStatus, Label rabbitStatus) {
        VBox dashboard = new VBox(10);
        dashboard.setPadding(new Insets(10));
        dashboard.setStyle("-fx-background-color: rgba(30, 41, 59, 0.5); -fx-background-radius: 10; -fx-border-color: rgba(255,255,255,0.1); -fx-border-radius: 10;");
        
        Label title = new Label("📊 REAL-TIME CLUSTER MONITOR");
        title.setStyle("-fx-font-weight: bold; -fx-text-fill: #60a5fa;");
        
        HBox nodesBox = new HBox(20);
        
        // MongoDB Health
        VBox mongoBox = new VBox(5);
        Label mongoTitle = new Label("MongoDB Replica Set:");
        mongoTitle.setStyle("-fx-font-size: 10px; -fx-text-fill: #94a3b8;");
        mongoBox.getChildren().addAll(mongoTitle, mongoStatus);
        
        // RabbitMQ Health
        VBox rabbitBox = new VBox(5);
        Label rabbitTitle = new Label("RabbitMQ Cluster:");
        rabbitTitle.setStyle("-fx-font-size: 10px; -fx-text-fill: #94a3b8;");
        rabbitBox.getChildren().addAll(rabbitTitle, rabbitStatus);
        
        nodesBox.getChildren().addAll(mongoBox, rabbitBox);
        dashboard.getChildren().addAll(title, nodesBox);
        
        return dashboard;
    }


    /**
     * Application entry point.
     * @param args command line arguments
     */
    public static void main(String[] args) {
        launch(args);
    }
}
