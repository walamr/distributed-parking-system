package edu.kinneret.parking.customer.ui;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.ParkingRepository;
import edu.kinneret.parking.common.RabbitMqConnectionManager;
import java.util.function.UnaryOperator;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
 * Main application class for the Customer UI.
 * Allows users to start/stop parking and view transaction history.
 */
public class CustomerApp extends Application {
    
    /**
     * Default constructor for CustomerApp.
     */
    public CustomerApp() {
    }
    
    static {
        System.setProperty("glass.win.uiScale", "1.0");
        System.setProperty("prism.allowhidpi", "false");
    }

    private static final Logger logger = LoggerFactory.getLogger(CustomerApp.class);

    /**
     * Initializes the JavaFX primary stage and scene.
     * 
     * @param primaryStage the primary window for the application
     */
    @Override
    public void start(Stage primaryStage) {
        logger.info("Starting Stage 2 Customer UI application");
        primaryStage.setTitle("Mulligan Parking System - Customer Cluster UI");
        primaryStage.setAlwaysOnTop(true);
        primaryStage.setResizable(false);
        primaryStage.setMinWidth(390);
        primaryStage.setMinHeight(844);
        primaryStage.setMaxWidth(390);
        primaryStage.setMaxHeight(844);
        primaryStage.maximizedProperty().addListener((obs, oldVal, newValue) -> {
            if (newValue) {
                primaryStage.setMaximized(false);
            }
        });

        Parent root = createContent(primaryStage);

        Scene scene = new Scene(root, 390, 844);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        try {
            scene.getStylesheets().add(getClass().getResource("/customer-style.css").toExternalForm());
        } catch (Exception e) {
            logger.warn("Could not load stylesheet: {}", e.getMessage());
        }

        primaryStage.setScene(scene);
        primaryStage.show();
    }

    /**
     * Creates the top navigation bar with header icons and hamburger menu.
     * 
     * @param onHamburgerClick action to execute when menu icon is clicked
     * @param showHeader whether to display the car icon and application titles
     * @return the top bar node
     */
    private Node createTopBar(Runnable onHamburgerClick, boolean showHeader) {
        VBox headerBox = new VBox(4);
        headerBox.setAlignment(Pos.CENTER);
        headerBox.setPadding(new Insets(10, 0, 20, 0));
        
        if (showHeader) {
            Label carHeaderIcon = new Label("🚗");
            carHeaderIcon.setStyle("-fx-font-size: 30px;");
            
            Label titleLabel = new Label("MULLIGAN PARKING");
            titleLabel.getStyleClass().add("header-label");
            
            Label subtitleLabel = new Label("Customer Portal");
            subtitleLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 13px; -fx-font-weight: bold;");
            
            headerBox.getChildren().addAll(carHeaderIcon, titleLabel, subtitleLabel);
        }

        Button hamburgerBtn = new Button("☰");
        hamburgerBtn.getStyleClass().add("hamburger-button");
        hamburgerBtn.setStyle("-fx-font-size: 32px; -fx-text-fill: white; -fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; -fx-cursor: hand;");
        hamburgerBtn.setTranslateY(-20);
        hamburgerBtn.setOnAction(e -> onHamburgerClick.run());

        Button logoutBtn = new Button("🚪");
        logoutBtn.setStyle("-fx-font-size: 24px; -fx-text-fill: #fca5a5; -fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; -fx-cursor: hand;");
        logoutBtn.setTranslateY(-20);
        logoutBtn.setOnAction(e -> {
            if (edu.kinneret.parking.common.ui.LoginController.onLogout != null) {
                edu.kinneret.parking.common.ui.LoginController.onLogout.run();
            }
        });

        StackPane topBar = new StackPane(headerBox, hamburgerBtn, logoutBtn);
        StackPane.setAlignment(hamburgerBtn, Pos.TOP_LEFT);
        StackPane.setMargin(hamburgerBtn, new Insets(0, 0, 0, 10));
        StackPane.setAlignment(logoutBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(logoutBtn, new Insets(0, 10, 0, 0));
        return topBar;
    }

    /**
     * Creates the main content pane including dashboard and history views.
     * Initializes the controller and binds it to UI components.
     * 
     * @param primaryStage the primary stage reference
     * @return the root parent node of the UI
     */
    @SuppressWarnings("unchecked")
    public Parent createContent(Stage primaryStage) {
        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.CUSTOMER_UI);
        ParkingRepository repository = new ParkingRepository(config);
        RabbitMqConnectionManager connectionManager = new RabbitMqConnectionManager(config);
        
        CustomerController controller = new CustomerController(config, repository, connectionManager);

        final Runnable[] toggleSidebarRef = new Runnable[1];

        // --- DASHBOARD HOME VIEW ---
        TextField vinField = new TextField();
        vinField.setPromptText("Vehicle VIN");
        vinField.setFocusTraversable(false);

        UnaryOperator<TextFormatter.Change> vinFilter = change -> {
            if (!change.isContentChange()) return change;
            String digits = change.getControlNewText().replaceAll("[^0-9]", "");
            if (digits.length() > 8) return null;
            StringBuilder formatted = new StringBuilder();
            for (int i = 0; i < digits.length(); i++) {
                if (i == 3 || i == 5) formatted.append("-");
                formatted.append(digits.charAt(i));
            }
            change.setText(formatted.toString());
            change.setRange(0, change.getControlText().length());
            change.setCaretPosition(formatted.length());
            change.setAnchor(formatted.length());
            return change;
        };
        vinField.setTextFormatter(new TextFormatter<>(vinFilter));

        String loggedInUser = edu.kinneret.parking.common.ui.LoginController.currentUsername;
        String associatedVin = edu.kinneret.parking.common.ui.LoginController.signupVins.get(loggedInUser != null ? loggedInUser : "customer");
        if (associatedVin == null || associatedVin.isBlank()) {
            associatedVin = "604-95-839";
        }
        vinField.setText(associatedVin);
        vinField.setEditable(false);
        vinField.setDisable(true);
        vinField.getStyleClass().add("text-field-fixed");

        TextField spaceNumberField = new TextField();
        spaceNumberField.setPromptText("Enter space number");
        spaceNumberField.getStyleClass().add("text-field-editable");

        Button startButton = new Button("🚗 Start Parking");
        startButton.getStyleClass().add("button-start");
        
        Button stopButton = new Button("■ Stop Parking");
        stopButton.getStyleClass().add("button-stop");

        Label timerLabel = new Label("00:00:00");
        timerLabel.getStyleClass().add("dashboard-value-secondary");
        
        Label timerCostLabel = new Label("0.00 NIS");
        timerCostLabel.getStyleClass().add("dashboard-value-secondary");

        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");
        statusLabel.setWrapText(true);
        statusLabel.setAlignment(Pos.CENTER);
        statusLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setMinHeight(45);
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        TextField rateLabel = new TextField();
        rateLabel.setEditable(false);
        rateLabel.setFocusTraversable(false);
        rateLabel.getStyleClass().add("dashboard-value-primary");

        VBox infoCard = new VBox(15);
        infoCard.getStyleClass().add("floating-card");
        infoCard.setVisible(false);
        infoCard.setManaged(false);

        HBox rateItem = new HBox(15);
        rateItem.setAlignment(Pos.CENTER_LEFT);
        Label rateHeader = new Label("RATE:");
        rateHeader.getStyleClass().add("dashboard-label");
        rateHeader.setMinWidth(64);
        rateItem.getChildren().addAll(rateHeader, rateLabel);
        
        HBox areaItem = new HBox(15);
        areaItem.setAlignment(Pos.CENTER_LEFT);
        Label areaHeader = new Label("AREA:");
        areaHeader.getStyleClass().add("dashboard-label");
        areaHeader.setMinWidth(64);
        Label areaLabel = new Label();
        areaLabel.getStyleClass().add("dashboard-value-secondary");
        areaItem.getChildren().addAll(areaHeader, areaLabel);
        
        Separator cardDivider = new Separator();
        cardDivider.setPadding(new Insets(5, 0, 5, 0));

        VBox dataRows = new VBox(10);
        HBox timeItem = new HBox(15);
        timeItem.setAlignment(Pos.CENTER_LEFT);
        Label timeHeader = new Label("TIME:");
        timeHeader.getStyleClass().add("dashboard-label");
        timeHeader.setMinWidth(64);
        timeItem.getChildren().addAll(timeHeader, timerLabel);

        HBox costItem = new HBox(15);
        costItem.setAlignment(Pos.CENTER_LEFT);
        Label costHeader = new Label("COST:");
        costHeader.getStyleClass().add("dashboard-label");
        costHeader.setMinWidth(64);
        costItem.getChildren().addAll(costHeader, timerCostLabel);
        
        dataRows.getChildren().addAll(timeItem, costItem);
        infoCard.getChildren().addAll(areaItem, rateItem, cardDivider, dataRows);

        VBox errorCard = new VBox();
        errorCard.setStyle("-fx-background-color: white; -fx-background-radius: 15; -fx-padding: 20;");
        errorCard.setVisible(false);
        errorCard.setManaged(false);
        
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #991b1b; -fx-font-weight: bold; -fx-font-size: 14px; -fx-border-color: #fca5a5; -fx-border-width: 1; -fx-border-radius: 8; -fx-background-color: #fef2f2; -fx-background-radius: 8; -fx-padding: 15;");
        errorLabel.setMaxWidth(Double.MAX_VALUE);
        errorLabel.setAlignment(Pos.CENTER);
        errorLabel.setWrapText(true);
        
        errorCard.getChildren().add(errorLabel);

        VBox vinBox = new VBox(5, new Label("Vehicle VIN"), vinField);
        vinBox.getChildren().get(0).setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");
        VBox spaceBox = new VBox(5, new Label("Parking Space Number *:"), spaceNumberField);
        spaceBox.getChildren().get(0).setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");
        VBox dashboardInputs = new VBox(16, vinBox, spaceBox);

        VBox dashboardView = new VBox(25);
        VBox historyView = new VBox(25);

        Hyperlink historyLink = new Hyperlink("Get Parking Events List");
        historyLink.setStyle("-fx-text-fill: #3b82f6; -fx-font-size: 14px; -fx-font-weight: bold;");
        historyLink.setOnAction(e -> {
            dashboardView.setVisible(false);
            dashboardView.setManaged(false);
            historyView.setVisible(true);
            historyView.setManaged(true);
            controller.handleEvents(false);
        });

        VBox dashboardActions = new VBox(12, startButton, stopButton);
        dashboardActions.setAlignment(Pos.CENTER);

        // --- RECOMMENDATION SECTION ---
        VBox recSection = new VBox(10);
        recSection.setStyle("-fx-background-color: rgba(255, 255, 255, 0.05); -fx-background-radius: 12; -fx-padding: 15; -fx-border-color: rgba(255, 255, 255, 0.1); -fx-border-width: 1; -fx-border-radius: 12;");
        
        Label recTitle = new Label("Parking space recommender:");
        recTitle.setStyle("-fx-text-fill: #cdd6f4; -fx-font-weight: bold; -fx-font-size: 14px;");

        ComboBox<String> recNodeCombo = new ComboBox<>();
        recNodeCombo.getItems().addAll("Recommender Node 1 (Port 8091)", "Recommender Node 2 (Port 8092)", "Recommender Node 3 (Port 8093)");
        recNodeCombo.setValue("Recommender Node 1 (Port 8091)");
        recNodeCombo.setMaxWidth(Double.MAX_VALUE);
        recNodeCombo.setStyle("-fx-background-color: #313244; -fx-text-fill: white; -fx-background-radius: 8;");

        Button recommendBtn = new Button("💡 Get Recommendation");
        recommendBtn.getStyleClass().add("button-start");
        recommendBtn.setStyle("-fx-background-color: #89b4fa; -fx-text-fill: #11111b; -fx-font-weight: bold; -fx-cursor: hand;");
        recommendBtn.setMaxWidth(Double.MAX_VALUE);

        Label recResultLabel = new Label("Enter space ID above and click get");
        recResultLabel.setStyle("-fx-text-fill: #a6adc8; -fx-font-size: 13px; -fx-alignment: center;");
        recResultLabel.setMaxWidth(Double.MAX_VALUE);
        recResultLabel.setAlignment(Pos.CENTER);

        recSection.getChildren().addAll(recTitle, recNodeCombo, recommendBtn, recResultLabel);

        Region dashboardSpacer = new Region();
        dashboardSpacer.setPrefHeight(40);

        dashboardView.getChildren().addAll(dashboardSpacer, createTopBar(() -> toggleSidebarRef[0].run(), true), dashboardInputs, dashboardActions, recSection, errorCard, statusLabel, infoCard, historyLink);
        dashboardView.setAlignment(Pos.TOP_CENTER);
        dashboardView.getStyleClass().add("glass-pane");
        dashboardView.setMaxWidth(380);
        dashboardView.setPadding(new Insets(30, 20, 30, 20));

        // --- PARKING HISTORY VIEW ---
        Button fetchHistoryBtn = new Button("🔍  Refresh History");
        fetchHistoryBtn.getStyleClass().add("button-history-toggle");
        fetchHistoryBtn.setMaxWidth(Double.MAX_VALUE);

        TableView<org.bson.Document> historyTable = new TableView<>();
        historyTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        historyTable.getStyleClass().add("history-table");
        historyTable.setFixedCellSize(45);
        Label placeholderLabel = new Label("No content in table");
        placeholderLabel.setStyle("-fx-text-fill: black; -fx-font-size: 14px;");
        historyTable.setPlaceholder(placeholderLabel);



        TableColumn<org.bson.Document, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(cell -> {
            Object tsObj = cell.getValue().get("timestamp");
            Long timestamp = (tsObj instanceof Number) ? ((Number) tsObj).longValue() : null;
            if (timestamp == null) {
                return new javafx.beans.property.SimpleStringProperty("-");
            }
            java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(timestamp), java.time.ZoneId.systemDefault());
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy");
            return new javafx.beans.property.SimpleStringProperty(dateTime.format(formatter));
        });

        TableColumn<org.bson.Document, String> startTimeCol = new TableColumn<>("Start Time");
        startTimeCol.setCellValueFactory(cell -> {
            Object tsObj = cell.getValue().get("startTimestamp");
            if (tsObj == null) {
                tsObj = cell.getValue().get("timestamp"); // fallback
            }
            Long timestamp = (tsObj instanceof Number) ? ((Number) tsObj).longValue() : null;
            if (timestamp == null) {
                return new javafx.beans.property.SimpleStringProperty("-");
            }
            java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(timestamp), java.time.ZoneId.systemDefault());
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
            return new javafx.beans.property.SimpleStringProperty(dateTime.format(formatter));
        });

        TableColumn<org.bson.Document, String> endTimeCol = new TableColumn<>("End Time");
        endTimeCol.setCellValueFactory(cell -> {
            Object tsObj = cell.getValue().get("endTimestamp");
            Long timestamp = (tsObj instanceof Number) ? ((Number) tsObj).longValue() : null;
            if (timestamp == null) {
                return new javafx.beans.property.SimpleStringProperty("-");
            }
            java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(timestamp), java.time.ZoneId.systemDefault());
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
            return new javafx.beans.property.SimpleStringProperty(dateTime.format(formatter));
        });
        
        TableColumn<org.bson.Document, String> spaceCol = new TableColumn<>("Space Number");
        spaceCol.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                ParkingRepository.readPayloadField(cell.getValue(), "spaceId")));

        TableColumn<org.bson.Document, String> areaCol = new TableColumn<>("Area");
        areaCol.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                ParkingRepository.readPayloadField(cell.getValue(), "areaName")));

        TableColumn<org.bson.Document, String> typeCol = new TableColumn<>("Status");
        typeCol.setCellValueFactory(cell -> {
            String rawType = cell.getValue().getString("type");
            String displayStatus = "Unknown";
            if ("transaction.start".equals(rawType)) {
                displayStatus = "Active";
            } else if ("transaction.stop".equals(rawType)) {
                displayStatus = "Finished";
            }
            return new javafx.beans.property.SimpleStringProperty(displayStatus);
        });

        TableColumn<org.bson.Document, String> costCol = new TableColumn<>("Cost");
        costCol.setCellValueFactory(cell -> {
            String cost = ParkingRepository.readPayloadField(cell.getValue(), "cost");
            if (cost == null || cost.isEmpty() || cost.equals("null") || cost.trim().equals("-")) {
                return new javafx.beans.property.SimpleStringProperty("-");
            }
            return new javafx.beans.property.SimpleStringProperty(cost + " NIS");
        });

        historyTable.getColumns().addAll(dateCol, startTimeCol, endTimeCol, spaceCol, areaCol, typeCol, costCol);

        Label historyTitle = new Label("PARKING HISTORY");
        historyTitle.getStyleClass().add("header-label");

        Label totalOwedLabel = new Label("Total Owed: 0.00 NIS");
        totalOwedLabel.setStyle("-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold; -fx-background-color: rgba(255,255,255,0.15); -fx-padding: 10 16; -fx-background-radius: 8; -fx-border-color: white; -fx-border-width: 2; -fx-border-radius: 8;");

        Label historyStatusLabel = new Label();
        historyStatusLabel.getStyleClass().add("status-label");
        historyStatusLabel.setWrapText(true);
        historyStatusLabel.setAlignment(Pos.CENTER);
        historyStatusLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        historyStatusLabel.setMaxWidth(Double.MAX_VALUE);
        historyStatusLabel.setMinHeight(45);
        historyStatusLabel.setVisible(false);
        historyStatusLabel.setManaged(false);

        VBox historyContent = new VBox(20, historyTitle, totalOwedLabel, fetchHistoryBtn, historyTable, historyStatusLabel);
        historyContent.setAlignment(Pos.TOP_CENTER);
        
        historyView.getChildren().addAll(new Region() {{ setPrefHeight(40); }}, createTopBar(() -\u003e toggleSidebarRef[0].run(), false), historyContent);
        historyView.getStyleClass().add("glass-pane");
        historyView.setVisible(false);
        historyView.setManaged(false);

        // --- NAVIGATION LOGIC ---
        StackPane viewsContainer = new StackPane(dashboardView, historyView);
        viewsContainer.setAlignment(Pos.TOP_CENTER);

        ScrollPane mainScrollPane = new ScrollPane(new VBox(viewsContainer));
        mainScrollPane.getStyleClass().add("customer-root-scroll");
        mainScrollPane.setFitToWidth(true);

        Region overlay = new Region();
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.45);");
        overlay.setVisible(false);
        overlay.setOpacity(0);

        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(292); 
        sidebar.setMaxWidth(292);
        sidebar.setTranslateX(-292);
        sidebar.setVisible(false);

        final boolean[] sidebarOpen = {false};
        toggleSidebarRef[0] = () -> {
            javafx.animation.ParallelTransition transition = new javafx.animation.ParallelTransition();
            javafx.animation.TranslateTransition slide = new javafx.animation.TranslateTransition(javafx.util.Duration.millis(300), sidebar);
            javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(300), overlay);
            if (sidebarOpen[0]) {
                slide.setToX(-292);
                fade.setToValue(0);
                transition.setOnFinished(event -> {
                    sidebar.setVisible(false);
                    overlay.setVisible(false);
                });
                sidebarOpen[0] = false;
            } else {
                sidebar.setVisible(true);
                overlay.setVisible(true);
                slide.setToX(0);
                fade.setToValue(1);
                sidebarOpen[0] = true;
            }
            transition.getChildren().addAll(slide, fade);
            transition.play();
        };

        VBox sidebarContent = new VBox(8);
        sidebarContent.getStyleClass().add("sidebar-content");
        VBox sidebarHeader = new VBox(4);
        sidebarHeader.getStyleClass().add("sidebar-header");
        Label sidebarLogo = new Label("MULLIGAN");
        Label sidebarSubLogo = new Label("PARKING SYSTEM");
        sidebarLogo.setStyle("-fx-text-fill: #0f172a; -fx-font-weight: 900; -fx-font-size: 24px;");
        sidebarSubLogo.setStyle("-fx-text-fill: #64748b; -fx-font-weight: bold; -fx-font-size: 12px; -fx-letter-spacing: 2px;");
        sidebarHeader.getChildren().addAll(sidebarLogo, sidebarSubLogo);

        Button sidebarHomeBtn = new Button("🏠  Dashboard Home");
        sidebarHomeBtn.getStyleClass().add("sidebar-button");
        sidebarHomeBtn.setMaxWidth(Double.MAX_VALUE);
        sidebarHomeBtn.setOnAction(e -> {
            if (sidebarOpen[0]) toggleSidebarRef[0].run();
            dashboardView.setVisible(true);
            dashboardView.setManaged(true);
            historyView.setVisible(false);
            historyView.setManaged(false);
        });

        Button sidebarHistoryBtn = new Button("📋  Parking History");
        sidebarHistoryBtn.getStyleClass().add("sidebar-button");
        sidebarHistoryBtn.setMaxWidth(Double.MAX_VALUE);
        sidebarHistoryBtn.setOnAction(e -> {
            if (sidebarOpen[0]) toggleSidebarRef[0].run();
            dashboardView.setVisible(false);
            dashboardView.setManaged(false);
            historyView.setVisible(true);
            historyView.setManaged(true);
            controller.handleEvents(false);
        });

        Button logoutButton = new Button("🚪  Logout Account");
        logoutButton.getStyleClass().addAll("sidebar-button", "sidebar-logout");
        logoutButton.setMaxWidth(Double.MAX_VALUE);
        logoutButton.setOnAction(event -> {
            if (edu.kinneret.parking.common.ui.LoginController.onLogout != null) {
                edu.kinneret.parking.common.ui.LoginController.onLogout.run();
            }
        });

        Region bottomSpacer = new Region();
        VBox.setVgrow(bottomSpacer, javafx.scene.layout.Priority.ALWAYS);
        Region safeAreaBottom = new Region();
        safeAreaBottom.setPrefHeight(60);

        sidebarContent.getChildren().addAll(sidebarHeader, sidebarHomeBtn, sidebarHistoryBtn, bottomSpacer, logoutButton, safeAreaBottom);
        VBox.setVgrow(sidebarContent, javafx.scene.layout.Priority.ALWAYS);
        sidebar.getChildren().add(sidebarContent);

        overlay.setOnMouseClicked(event -> { if (sidebarOpen[0]) toggleSidebarRef[0].run(); });

        // CONTROLLER BINDING
        controller.attach(vinField, spaceNumberField, statusLabel, historyTable, rateLabel, areaLabel, totalOwedLabel, errorLabel, errorCard);
        controller.attachButtons(startButton, stopButton, fetchHistoryBtn);
        controller.attachTimer(infoCard, timerLabel, timerCostLabel, rateItem, timeItem, costItem, cardDivider);
        controller.attachHistoryStatus(historyStatusLabel);
        controller.attachRecommender(recommendBtn, recNodeCombo, recResultLabel);

        // Restore persisted local transactions from disk (fix: stop operations survive app restarts)
        controller.loadPersistedTransactions(associatedVin);

        // Fetch initial history to resume any active session from previous logins
        controller.handleEvents(false);

        StackPane root = new StackPane();
        root.getStyleClass().add("portal-container");
        root.setPrefSize(390, 844);

        try {
            Image bgImage = new Image(getClass().getResourceAsStream("/customer-bg.png"));
            ImageView bgView = new ImageView(bgImage);
            bgView.setFitWidth(390);
            bgView.setFitHeight(844);
            root.getChildren().add(bgView);
        } catch (Exception e) {
            root.setStyle("-fx-background-color: linear-gradient(to bottom right, #0f172a, #1e1b4b, #312e81);");
        }

        StackPane mainStack = new StackPane(mainScrollPane, overlay, sidebar);
        StackPane.setAlignment(sidebar, Pos.TOP_LEFT);
        root.getChildren().add(mainStack);
        return root;
    }

    /**
     * Application entry point.
     * 
     * @param args command line arguments
     */
    public static void main(String[] args) {
        launch(args);
    }
}
