package edu.kinneret.parking.peo.ui;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.ParkingRepository;
import edu.kinneret.parking.common.RabbitMqConnectionManager;
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

import java.util.function.UnaryOperator;

/**
 * Main application class for the Parking Enforcement Officer (PEO) UI.
 * Provides functionality for checking vehicle legality and issuing citations.
 */
public class PEOApp extends Application {
    /**
     * Default constructor for PEOApp.
     */
    public PEOApp() {
        // Default constructor
    }
    
    static {
        System.setProperty("glass.win.uiScale", "1.0");
        System.setProperty("prism.allowhidpi", "false");
    }

    private static final Logger logger = LoggerFactory.getLogger(PEOApp.class);

    /**
     * Initializes and shows the primary stage for the PEO application.
     * 
     * @param primaryStage the primary stage for this application
     */
    @Override
    public void start(Stage primaryStage) {
        logger.info("Starting Cluster-Aware PEO UI...");
        primaryStage.setTitle("Mulligan Parking System - Enforcement Cluster UI");
        primaryStage.setAlwaysOnTop(true);
        primaryStage.setResizable(false);
        
        javafx.geometry.Rectangle2D bounds = javafx.stage.Screen.getPrimary().getBounds();
        double targetHeight = bounds.getHeight();
        double scaleFactor = targetHeight / 844.0;
        double targetWidth = 390.0 * scaleFactor;
        
        primaryStage.setMinWidth(targetWidth);
        primaryStage.setMinHeight(targetHeight);
        primaryStage.setMaxWidth(targetWidth);
        primaryStage.setMaxHeight(targetHeight);
        
        primaryStage.setX((bounds.getWidth() - targetWidth) / 2.0); // Center horizontally
        primaryStage.setY(0.0); // Align to the absolute top edge of the screen
        primaryStage.maximizedProperty().addListener((obs, oldVal, newValue) -> {
            if (newValue) {
                primaryStage.setMaximized(false);
            }
        });
        
        Parent root = createContent();
        javafx.scene.Group scaleGroup = new javafx.scene.Group(root);
        scaleGroup.getTransforms().add(new javafx.scene.transform.Scale(scaleFactor, scaleFactor));
        
        Scene scene = new Scene(scaleGroup, targetWidth, targetHeight);
        try {
            scene.getStylesheets().add(getClass().getResource("/peo-style.css").toExternalForm());
        } catch (Exception e) {}

        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private VBox createFieldBox(String title, Control field) {
        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");
        Label asterisk = new Label(" *");
        asterisk.setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold; -fx-font-size: 14px;");
        HBox labelBox = new HBox(titleLbl, asterisk);
        return new VBox(8, labelBox, field);
    }

    /**
     * Creates the main UI content pane for the PEO application.
     * Initializes the controller and sets up UI components and layouts.
     * 
     * @return the root parent node of the UI
     */
    @SuppressWarnings("unchecked")
    public Parent createContent() {
        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.PEO_UI);
        ParkingRepository repository = new ParkingRepository(config);
        RabbitMqConnectionManager rabbitManager = new RabbitMqConnectionManager(config);
        
        PEOController controller = new PEOController(config, repository, rabbitManager);

        // --- MAIN VIEW COMPONENTS ---
        VBox headerCard = new VBox(5);
        headerCard.setAlignment(Pos.CENTER);
        headerCard.setStyle("-fx-background-color: #2d3748; -fx-background-radius: 20; -fx-padding: 20; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 10, 0, 0, 5);");
        
        Label iconLabel = new Label("👮");
        iconLabel.setStyle("-fx-font-size: 40px; -fx-text-fill: white;");
        
        Label titleLabel = new Label("ENFORCEMENT UNIT");
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 18px;");
        
        Label subtitleLabel = new Label("Mulligan Parking Control");
        subtitleLabel.setStyle("-fx-text-fill: #a0aec0; -fx-font-size: 13px;");

        headerCard.getChildren().addAll(iconLabel, titleLabel, subtitleLabel);

        Button logoutBtn = new Button("🚪");
        logoutBtn.setStyle("-fx-font-size: 20px; -fx-text-fill: #fca5a5; -fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; -fx-cursor: hand;");
        logoutBtn.setOnAction(e -> {
            if (edu.kinneret.parking.common.ui.LoginController.onLogout != null) {
                edu.kinneret.parking.common.ui.LoginController.onLogout.run();
            }
        });
        
        StackPane headerStack = new StackPane(headerCard, logoutBtn);
        StackPane.setAlignment(logoutBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(logoutBtn, new Insets(10, 10, 0, 0));

        TextField vinField = new TextField();
        
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
        VBox vinBox = createFieldBox("Vehicle VIN", vinField);

        TextField spaceNumberField = new TextField();
        VBox spaceBox = createFieldBox("Parking Space Number", spaceNumberField);

        TextField enforcerIdField = new TextField();
        String loggedInEnforcer = edu.kinneret.parking.common.ui.LoginController.currentUsername;
        String enforcerId = edu.kinneret.parking.common.ui.LoginController.signupVins.get(loggedInEnforcer != null ? loggedInEnforcer : "peo_service");
        enforcerIdField.setText(enforcerId != null && !enforcerId.isBlank()
                ? enforcerId : "123456789");
        enforcerIdField.setEditable(false);
        enforcerIdField.setFocusTraversable(false);
        enforcerIdField.setStyle(
                "-fx-background-color: #374151; " +
                "-fx-text-fill: #9ca3af; " +
                "-fx-border-color: #4b5563; " +
                "-fx-border-radius: 8; " +
                "-fx-background-radius: 8; " +
                "-fx-padding: 10 14; " +
                "-fx-font-size: 14px; " +
                "-fx-cursor: default;");
        VBox enforcerBox = createFieldBox("Enforcer ID", enforcerIdField);

        TextField citationCostField = new TextField();
        VBox costBox = createFieldBox("Citation Cost (NIS)", citationCostField);

        TextField citationReasonField = new TextField();
        VBox reasonBox = createFieldBox("Violation Reason", citationReasonField);

        Label alertLabel = new Label("");
        alertLabel.getStyleClass().add("alert-label");
        alertLabel.setWrapText(true);
        alertLabel.setAlignment(Pos.CENTER);

        Button checkButton = new Button("🔍 CHECK VEHICLE");
        checkButton.setMaxWidth(Double.MAX_VALUE);
        checkButton.setStyle("-fx-background-color: #d97706; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 12; -fx-background-radius: 12;");

        Button citeButton = new Button("⚖ ISSUE CITATION");
        citeButton.setMaxWidth(Double.MAX_VALUE);
        citeButton.setStyle("-fx-background-color: #d97706; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 12; -fx-background-radius: 12;");

        VBox citationSection = new VBox(15);
        citationSection.setVisible(false);
        citationSection.setManaged(false);
        citationSection.getChildren().addAll(enforcerBox, costBox, reasonBox, citeButton);

        // --- HAMBURGER & NAVIGATION ---
        StackPane viewsContainer = new StackPane();

        Button hamburgerMainBtn = new Button("☰");
        hamburgerMainBtn.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: white; -fx-font-size: 20px; -fx-background-radius: 8; -fx-padding: 2 10; -fx-cursor: hand; -fx-border-color: rgba(255,255,255,0.2); -fx-border-radius: 8;");
        HBox topBarMain = new HBox(hamburgerMainBtn);
        topBarMain.setPadding(new Insets(40, 20, 0, 20));

        VBox mainViewContent = new VBox(20, headerStack, vinBox, spaceBox, checkButton, alertLabel, citationSection);
        mainViewContent.setPadding(new Insets(20, 20, 40, 20));
        mainViewContent.setAlignment(Pos.TOP_CENTER);
        
        ScrollPane mainScroll = new ScrollPane(mainViewContent);
        mainScroll.setFitToWidth(true);
        mainScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        VBox mainView = new VBox(topBarMain, mainScroll);
        mainView.getStyleClass().add("glass-pane");

        // --- HISTORY VIEW ---
        Button hamburgerHistoryBtn = new Button("☰");
        hamburgerHistoryBtn.setStyle("-fx-background-color: #d97706; -fx-text-fill: white; -fx-font-size: 20px; -fx-background-radius: 8; -fx-padding: 2 10; -fx-cursor: hand;");
        HBox topBarHistory = new HBox(hamburgerHistoryBtn);
        topBarHistory.setPadding(new Insets(40, 20, 0, 20));

        Label historyTitle = new Label("ACTIVITY HISTORY");
        historyTitle.setStyle("-fx-text-fill: #d97706; -fx-font-weight: bold; -fx-font-size: 22px;");
        Label historySubtitle = new Label("SESSION ACTIVITY LOG");
        historySubtitle.setStyle("-fx-text-fill: white; -fx-font-size: 12px;");
        VBox historyHeader = new VBox(5, historyTitle, historySubtitle);
        historyHeader.setAlignment(Pos.CENTER);
        historyHeader.setPadding(new Insets(10, 0, 20, 0));

        TableView<PEOController.ActivityLogEntry> activityTable = new TableView<>();
        activityTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        // Default white table styling for JavaFX fits the mockup
        
        TableColumn<PEOController.ActivityLogEntry, String> actionCol = new TableColumn<>("Action");
        actionCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().getAction()));
        actionCol.setStyle("-fx-text-fill: black; -fx-font-weight: bold;");

        TableColumn<PEOController.ActivityLogEntry, String> vinCol = new TableColumn<>("VIN");
        vinCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().getVin()));
        vinCol.setStyle("-fx-text-fill: black; -fx-font-weight: bold;");

        TableColumn<PEOController.ActivityLogEntry, String> resultCol = new TableColumn<>("Result");
        resultCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().getResult()));
        resultCol.setStyle("-fx-text-fill: black; -fx-font-weight: bold;");

        activityTable.getColumns().addAll(actionCol, vinCol, resultCol);

        VBox historyContent = new VBox(historyHeader, activityTable);
        historyContent.setPadding(new Insets(0, 20, 20, 20));
        VBox.setVgrow(activityTable, Priority.ALWAYS);

        VBox historyView = new VBox(topBarHistory, historyContent);
        historyView.setVisible(false);
        historyView.getStyleClass().add("glass-pane");
        historyView.setStyle("-fx-background-color: rgba(0,0,0,0.6);");

        hamburgerMainBtn.setOnAction(e -> {
            mainView.setVisible(false);
            historyView.setVisible(true);
        });
        
        hamburgerHistoryBtn.setOnAction(e -> {
            historyView.setVisible(false);
            mainView.setVisible(true);
        });

        viewsContainer.getChildren().addAll(mainView, historyView);

        controller.attach(vinField, spaceNumberField, enforcerIdField, citationCostField, citationReasonField, alertLabel, citationSection, activityTable);
        controller.attachButtons(checkButton, citeButton);

        StackPane root = new StackPane();
        try {
            Image bgImage = new Image(getClass().getResourceAsStream("/peo-bg.png"));
            ImageView bgView = new ImageView(bgImage);
            bgView.setFitWidth(390);
            bgView.setFitHeight(844);
            root.getChildren().add(bgView);
        } catch (Exception e) {}

        root.getChildren().add(viewsContainer);
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
