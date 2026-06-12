package edu.kinneret.parking.customer.ui;

import edu.kinneret.parking.common.*;
import edu.kinneret.parking.customer.RecommenderRequestSigner;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.SSLSocket;

/**
 * Controller for the Customer UI.
 * Adapted for Stage 2: Cluster-aware with HMAC signing.
 */
public class CustomerController {
    private static final Logger logger = LoggerFactory.getLogger(CustomerController.class);

    private final AppConfig config;
    private final ParkingRepository repository;
    private final RabbitMqConnectionManager rabbitManager;
    private final SecureMessageSigner signer;

    private TextField vinField;
    private TextField spaceNumberField;
    private Label statusLabel;
    private TableView<Document> historyTable;
    private TextField rateLabel;
    private Label areaLabel;
    private Label totalOwedLabel;
    private Label historyStatusLabel;
    private Label errorLabel;
    private javafx.scene.layout.VBox errorCard;
    private javafx.animation.PauseTransition historyStatusTimer;
    private javafx.animation.PauseTransition dashboardStatusTimer;
    private javafx.scene.layout.VBox infoCard;

    private final ObservableList<Document> historyItems = FXCollections.observableArrayList();
    private final java.util.Map<String, Document> spaceCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final List<Document> localOfflineTransactions = new java.util.concurrent.CopyOnWriteArrayList<>();

    // --- Local persistence for offline transactions ---
    private static final String LOCAL_TX_DIR = "local_parking_data";

    /**
     * Returns the path to the local transactions file for the given VIN.
     * Stored as: local_parking_data/<vin>.json
     */
    private static Path getLocalTxFile(String vin) {
        String safeVin = vin.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return Paths.get(LOCAL_TX_DIR, safeVin + ".json");
    }

    /**
     * Loads local offline transactions from disk for the given VIN.
     * Called once at startup after VIN is known.
     */
    private static void loadLocalTransactions(String vin) {
        if (vin == null || vin.isBlank())
            return;
        Path file = getLocalTxFile(vin);
        if (!Files.exists(file))
            return;
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (content.isEmpty() || content.equals("[]"))
                return;
            
            com.google.gson.JsonArray jsonArray = com.google.gson.JsonParser.parseString(content).getAsJsonArray();
            localOfflineTransactions.clear();
            for (com.google.gson.JsonElement el : jsonArray) {
                try {
                    Document doc = Document.parse(el.getAsJsonObject().toString());
                    localOfflineTransactions.add(doc);
                } catch (Exception ignored) {
                }
            }
            logger.info("Loaded {} local offline transactions for VIN: {}", localOfflineTransactions.size(), vin);
        } catch (Exception e) {
            logger.warn("Could not load local transactions for VIN {}: {}", vin, e.getMessage());
        }
    }

    /**
     * Saves the current localOfflineTransactions list to disk for the given VIN.
     */
    private static void saveLocalTransactions(String vin) {
        if (vin == null || vin.isBlank())
            return;
        try {
            Path dir = Paths.get(LOCAL_TX_DIR);
            if (!Files.exists(dir))
                Files.createDirectories(dir);
            Path file = getLocalTxFile(vin);
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Document doc : localOfflineTransactions) {
                if (!first)
                    sb.append(",");
                sb.append(doc.toJson());
                first = false;
            }
            sb.append("]");
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            logger.warn("Could not save local transactions for VIN {}: {}", vin, e.getMessage());
        }
    }

    /**
     * Creates a new CustomerController with the required infrastructure.
     * 
     * @param config        the application configuration
     * @param repository    the MongoDB repository
     * @param rabbitManager the RabbitMQ connection manager
     */
    public CustomerController(AppConfig config, ParkingRepository repository, RabbitMqConnectionManager rabbitManager) {
        this.config = config;
        this.repository = repository;
        this.rabbitManager = rabbitManager;
        this.signer = new SecureMessageSigner(config.getHmacSecret());
    }

    /**
     * Must be called after VIN is known (after attach()) to restore persisted local
     * transactions.
     * 
     * @param vin the vehicle identification number of the logged-in user
     */
    public void loadPersistedTransactions(String vin) {
        loadLocalTransactions(vin);
    }

    /**
     * Attaches UI fields to the controller.
     * 
     * @param vinField         the VIN input field
     * @param spaceNumberField the space number input field
     * @param statusLabel      the status display label
     * @param historyTable     the transaction history table
     * @param rateLabel        the rate display field
     * @param areaLabel        the zone display label
     * @param totalOwedLabel   the total owed display label
     * @param errorLabel       the error label
     * @param errorCard        the error container card
     */
    public void attach(TextField vinField, TextField spaceNumberField, Label statusLabel,
            TableView<Document> historyTable, TextField rateLabel, Label areaLabel, Label totalOwedLabel,
            Label errorLabel, javafx.scene.layout.VBox errorCard) {
        this.vinField = vinField;
        this.spaceNumberField = spaceNumberField;
        this.statusLabel = statusLabel;
        this.historyTable = historyTable;
        this.rateLabel = rateLabel;
        this.areaLabel = areaLabel;
        this.totalOwedLabel = totalOwedLabel;
        this.errorLabel = errorLabel;
        this.errorCard = errorCard;

        this.errorLabel.textProperty().addListener((obs, oldVal, newVal) -> {
            boolean visible = newVal != null && !newVal.isEmpty();
            this.errorCard.setVisible(visible);
            this.errorCard.setManaged(visible);
        });

        this.historyTable.setItems(historyItems);

        javafx.beans.binding.IntegerBinding sizeBinding = javafx.beans.binding.Bindings.size(historyItems);
        this.historyTable.prefHeightProperty().bind(
                javafx.beans.binding.Bindings.createDoubleBinding(() -> {
                    int size = sizeBinding.get();
                    if (size == 0)
                        return 90.0;
                    return size * 45.0 + 42.0; // 42px for header + slight border
                }, sizeBinding));
        this.historyTable.minHeightProperty().bind(this.historyTable.prefHeightProperty());
        this.historyTable.maxHeightProperty().bind(this.historyTable.prefHeightProperty());

        this.spaceNumberField.textProperty().addListener((obs, oldVal, newVal) -> {
            // Clear error message as soon as the user starts typing
            if (newVal != null && !newVal.isEmpty()) {
                this.errorLabel.setText("");
            }
            fetchRateAndZone(newVal);
        });
    }

    /**
     * Attaches action buttons to the controller.
     * 
     * @param startButton   the start parking button
     * @param stopButton    the stop parking button
     * @param historyButton the history refresh button
     */
    public void attachButtons(Button startButton, Button stopButton, Button historyButton) {

        startButton.setOnAction(e -> handleStart());
        stopButton.setOnAction(e -> handleStop());
        historyButton.setOnAction(e -> handleEvents(true));
    }

    /**
     * Attaches action buttons to the controller.
     *
     * @param startButton the start parking button
     * @param stopButton the stop parking button
     * @param historyButton the history refresh button
     * @param recommendButton the explicit Recommend Parking button
     */
    public void attachButtons(Button startButton, Button stopButton, Button historyButton, Button recommendButton) {
        attachButtons(startButton, stopButton, historyButton);
        recommendButton.setOnAction(e -> handleRecommendParking());
    }

    private java.time.Instant parkingStartTime;
    private String activeSpace = null;
    private String lastActiveSpaceBeforeStop = null;
    private javafx.animation.Timeline timeline;
    private Label timerCostLabel;

    /**
     * Attaches the dynamic info card components.
     * 
     * @param infoCard       the container for dynamic info
     * @param timerLabel     the label for current duration
     * @param timerCostLabel the label for current cost
     * @param rateItem       layout item
     * @param timeItem       layout item
     * @param costItem       layout item
     * @param cardDivider    layout item
     * @param requestItem    layout item
     * @param resultItem     layout item
     * @param recDivider     layout item
     * @param requestLabel   the label showing the request
     */
    public void attachTimer(VBox infoCard, Label timerLabel, Label timerCostLabel, HBox rateItem, HBox timeItem,
            HBox costItem, Separator cardDivider, HBox requestItem, HBox resultItem, Separator recDivider, Label requestLabel) {
        this.timerCostLabel = timerCostLabel;
        this.infoCard = infoCard;

        timeline = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> {
            if (parkingStartTime != null) {
                long elapsedSeconds = java.time.Duration.between(parkingStartTime, java.time.Instant.now())
                        .getSeconds();
                long h = elapsedSeconds / 3600;
                long m = (elapsedSeconds % 3600) / 60;
                long s = elapsedSeconds % 60;
                timerLabel.setText(String.format("%02d:%02d:%02d", h, m, s));

                try {
                    String rateText = rateLabel.getText().replace(" NIS/hr", "");
                    BigDecimal rate = new BigDecimal(rateText);
                    BigDecimal hours = BigDecimal.valueOf(elapsedSeconds).divide(BigDecimal.valueOf(3600), 4,
                            java.math.RoundingMode.HALF_UP);
                    BigDecimal cost = rate.multiply(hours).setScale(2, java.math.RoundingMode.HALF_UP);
                    timerCostLabel.setText(String.format("%.2f NIS", cost));
                } catch (Exception ex) {
                    // Ignore parsing errors if rate is not yet loaded
                }
            }
        }));
        timeline.setCycleCount(javafx.animation.Animation.INDEFINITE);
        timeline.play();

        javafx.beans.value.ChangeListener<Object> visibilityListener = (obs, oldVal, newVal) -> {
            boolean hasRate = rateLabel != null && rateLabel.getText() != null && !rateLabel.getText().isBlank();
            boolean hasStatus = statusLabel != null && statusLabel.getText() != null
                    && !statusLabel.getText().isBlank();
            boolean isParked = parkingStartTime != null;
            boolean isJustStopped = statusLabel != null && statusLabel.getText() != null
                    && statusLabel.getText().contains("Stopped");
            
            String inputSpace = spaceNumberField != null && spaceNumberField.getText() != null ? spaceNumberField.getText().trim() : "";
            boolean isMatchingActiveSpace = isParked && inputSpace.equalsIgnoreCase(activeSpace);
            boolean isMatchingJustStopped = isJustStopped && inputSpace.equalsIgnoreCase(lastActiveSpaceBeforeStop);
            
            boolean showSessionData = isMatchingActiveSpace || isMatchingJustStopped;
            boolean hasRecommendation = requestLabel != null && requestLabel.getText() != null && !"-".equals(requestLabel.getText());
            boolean showRec = !showSessionData && hasRecommendation;
            boolean shouldShowCard = hasRate || hasStatus || showSessionData || showRec;

            Platform.runLater(() -> {
                if (this.infoCard != null) {
                    this.infoCard.setVisible(shouldShowCard);
                    this.infoCard.setManaged(shouldShowCard);
                }
                if (rateItem != null) {
                    rateItem.setVisible(hasRate);
                    rateItem.setManaged(hasRate);
                }
                if (timeItem != null) {
                    timeItem.setVisible(showSessionData);
                    timeItem.setManaged(showSessionData);
                }
                if (costItem != null) {
                    costItem.setVisible(showSessionData);
                    costItem.setManaged(showSessionData);
                }
                if (cardDivider != null) {
                    boolean showDivider = hasRate && showSessionData;
                    cardDivider.setVisible(showDivider);
                    cardDivider.setManaged(showDivider);
                }
                if (requestItem != null) {
                    requestItem.setVisible(showRec);
                    requestItem.setManaged(showRec);
                }
                if (resultItem != null) {
                    resultItem.setVisible(showRec);
                    resultItem.setManaged(showRec);
                }
                if (recDivider != null) {
                    recDivider.setVisible(showRec);
                    recDivider.setManaged(showRec);
                }
            });
        };

        if (rateLabel != null) {
            rateLabel.textProperty().addListener(visibilityListener);
        }
        if (statusLabel != null) {
            statusLabel.textProperty().addListener(visibilityListener);
        }
        if (spaceNumberField != null) {
            spaceNumberField.textProperty().addListener(visibilityListener);
        }
        if (requestLabel != null) {
            requestLabel.textProperty().addListener(visibilityListener);
        }
    }

    /**
     * Attaches the history status display label to the controller.
     *
     * @param historyStatusLabel the label used to show history fetch status
     */
    public void attachHistoryStatus(Label historyStatusLabel) {
        this.historyStatusLabel = historyStatusLabel;
    }

    /**
     * Handles the 'Start Parking' action by publishing a signed message to
     * RabbitMQ.
     */
    private void handleStart() {
        String rawVin = vinField.getText();
        String vin = rawVin == null ? "" : rawVin.trim();
        String rawSpace = spaceNumberField.getText();
        String spaceId = rawSpace == null ? "" : rawSpace.trim();

        if (vin.isEmpty() || vin.replace("-", "").trim().isEmpty()) {
            setStatus("Error: Vehicle number is required.", true);
            return;
        }

        if (spaceId.isEmpty()) {
            setStatus("Error: Space ID is required.", true);
            return;
        }

        if (spaceId.equalsIgnoreCase(activeSpace) && parkingStartTime != null) {
            setStatus("Error: Parking is already active in this space.", true);
            return;
        }

        try {
            ValidationUtils.requireValidVehicleId(vin.toUpperCase());
            ValidationUtils.requireValidSpaceId(spaceId.toUpperCase());
            if (spaceId.matches("\\d+")) {
                int num = Integer.parseInt(spaceId);
                if (num > 100) {
                    setStatus("Error: Space does not exist.", true);
                    return;
                }
            }
        } catch (IllegalArgumentException ex) {
            setStatus("Error: Invalid vehicle or parking space format.", true);
            return;
        }
        try {
            if (!repository.isVehicleRegistered(vin)) {
                setStatus("Error: Vehicle " + vin + " is not registered in the system.", true);
                return;
            }
        } catch (Exception ex) {
            // If DB is unreachable, allow parking to proceed (offline mode)
            System.out
                    .println("[CustomerUI] Could not verify vehicle registration (DB offline). Proceeding with start.");
        }

        final String oldActiveSpace = activeSpace;
        final java.time.Instant oldParkingStartTime = parkingStartTime;

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                String areaName = areaLabel != null ? areaLabel.getText() : "Unknown";

                // Check database for space occupancy
                try {
                    Document latestTx = repository.getLatestTransactionForSpace(spaceId);
                    if (latestTx != null) {
                        String action = ParkingRepository.readTransactionAction(latestTx);
                        if ("start".equalsIgnoreCase(action)) {
                            throw new IllegalStateException("Space " + spaceId + " is already occupied.");
                        }
                    }
                } catch (IllegalStateException ex) {
                    throw ex;
                } catch (Exception ex) {
                    System.out.println("[CustomerUI] Warning checking space occupancy: " + ex.getMessage());
                    logger.warn("Database offline or unreachable while checking space occupancy: "
                            + ex.getMessage() + ". Proceeding with start.");
                }

                // Also check local offline transactions for space occupancy
                Document localLatest = null;
                for (Document doc : localOfflineTransactions) {
                    String sId = ParkingRepository.readPayloadField(doc, "spaceId");
                    if (spaceId.equalsIgnoreCase(sId)) {
                        localLatest = doc;
                        break;
                    }
                }
                if (localLatest != null) {
                    String action = ParkingRepository.readTransactionAction(localLatest);
                    if ("start".equalsIgnoreCase(action)) {
                        throw new IllegalStateException("Space " + spaceId + " is already occupied.");
                    }
                }

                // 1. First, check if there was a local active session in the UI.
                boolean stoppedLocalSession = false;
                if (oldParkingStartTime != null && oldActiveSpace != null) {
                    System.out.println(
                            "[CustomerUI] Checking auto-stop for local active session in space: " + oldActiveSpace);
                    try {
                        long elapsedSeconds = java.time.Duration.between(oldParkingStartTime, java.time.Instant.now())
                                .getSeconds();
                        if (elapsedSeconds < 0)
                            elapsedSeconds = 0;

                        BigDecimal rate = repository.getSpaceRate(oldActiveSpace);
                        if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) {
                            // Fallback local calculation
                            try {
                                String digits = oldActiveSpace.replaceAll("[^\\d]", "");
                                if (!digits.isEmpty()) {
                                    int id = Integer.parseInt(digits);
                                    if (id >= 1 && id <= 100) {
                                        double[] rates = { 1.77, 70.23, 56.33, 24.36, 43.27, 35.37, 87.99, 56.22, 17.29,
                                                55.27 };
                                        rate = BigDecimal.valueOf(rates[(id - 1) % 10]);
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                            if (rate == null) {
                                rate = new BigDecimal("12.5");
                            }
                        }
                        BigDecimal hours = BigDecimal.valueOf(elapsedSeconds).divide(BigDecimal.valueOf(3600), 4,
                                java.math.RoundingMode.HALF_UP);
                        String costString = rate.multiply(hours).setScale(2, java.math.RoundingMode.HALF_UP).toString();

                        String localOldArea = repository.getSpaceZone(oldActiveSpace);
                        if (localOldArea == null || localOldArea.isEmpty() || "Unknown".equals(localOldArea)) {
                            localOldArea = "Unknown";
                        }

                        // Add stop doc to local offline transactions
                        Document autoStopDoc = new Document("type", "transaction.stop")
                                .append("payload", new Document("vehicleId", vin)
                                        .append("spaceId", oldActiveSpace)
                                        .append("areaName", localOldArea)
                                        .append("cost", costString))
                                .append("timestamp", java.time.Instant.now().getEpochSecond());
                        localOfflineTransactions.add(0, autoStopDoc);

                        String stopPayload = String.format(
                                "{\"vehicleId\":\"%s\",\"spaceId\":\"%s\",\"areaName\":\"%s\",\"type\":\"stop\",\"cost\":\"%s\"}",
                                vin, oldActiveSpace, localOldArea, costString);
                        String stopClientIp = InetAddress.getLocalHost().getHostAddress();
                        MessageEnvelope stopEnv = MessageEnvelope.createUnsigned("transaction.stop", stopPayload,
                                stopClientIp, UUID.randomUUID().toString()).sign(signer);

                        try {
                            rabbitManager.withChannel((channel, node) -> {
                                channel.basicPublish("", config.getTransactionsQueueName(), null,
                                        stopEnv.toJsonString().getBytes(StandardCharsets.UTF_8));
                            });
                            System.out.println("[CustomerUI] Auto-stopped previous local active session for VIN: " + vin
                                    + " in space " + oldActiveSpace + ", cost: " + costString);
                            logger.info("Auto-stopped previous local active session for VIN: " + vin + " in space "
                                    + oldActiveSpace);
                        } catch (Exception qex) {
                            System.out.println(
                                    "[CustomerUI] Queue offline while auto-stopping previous local session for VIN: "
                                            + vin + ", space: " + oldActiveSpace);
                            logger.warn(
                                    "Queue offline while auto-stopping previous local active session for VIN: " + vin);
                        }
                        stoppedLocalSession = true;
                    } catch (Exception ex) {
                        System.out.println("[CustomerUI] Error auto-stopping local active session: " + ex.getMessage());
                        logger.error("Error auto-stopping local active session: " + ex.getMessage(), ex);
                    }
                }

                // 2. SUC 1 Branch B: Check if there is already an active parking event in the
                // database (if not already stopped locally)
                if (!stoppedLocalSession) {
                    System.out.println("[CustomerUI] Checking auto-stop for active DB sessions...");
                    try {
                        List<Document> history = repository.getVehicleHistory(vin);
                        if (!history.isEmpty()) {
                            Document lastEvent = history.get(0);
                            if ("transaction.start".equals(lastEvent.getString("type"))) {
                                // There's an active event. Auto-stop it!
                                String dbSpaceId = ParkingRepository.readPayloadField(lastEvent, "spaceId");
                                String dbArea = ParkingRepository.readPayloadField(lastEvent, "areaName");
                                System.out.println("[CustomerUI] Found active DB session in space: " + dbSpaceId
                                        + ". Auto-stopping...");
                                Long startTimestamp = getLongSafe(lastEvent, "timestamp");
                                String costString = "0.00";
                                if (startTimestamp != null) {
                                    long elapsedSeconds = java.time.Instant.now().getEpochSecond() - startTimestamp;
                                    if (elapsedSeconds < 0)
                                        elapsedSeconds = 0;

                                    BigDecimal rate = repository.getSpaceRate(dbSpaceId);
                                    if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) {
                                        // Fallback rate
                                        try {
                                            String digits = dbSpaceId.replaceAll("[^\\d]", "");
                                            if (!digits.isEmpty()) {
                                                int id = Integer.parseInt(digits);
                                                if (id >= 1 && id <= 100) {
                                                    double[] rates = { 1.77, 70.23, 56.33, 24.36, 43.27, 35.37, 87.99,
                                                            56.22, 17.29, 55.27 };
                                                    rate = BigDecimal.valueOf(rates[(id - 1) % 10]);
                                                }
                                            }
                                        } catch (Exception ignored) {
                                        }
                                        if (rate == null) {
                                            rate = new BigDecimal("12.5");
                                        }
                                    }
                                    BigDecimal hours = BigDecimal.valueOf(elapsedSeconds)
                                            .divide(BigDecimal.valueOf(3600), 4, java.math.RoundingMode.HALF_UP);
                                    costString = rate.multiply(hours).setScale(2, java.math.RoundingMode.HALF_UP)
                                            .toString();
                                }

                                Document autoStopDoc = new Document("type", "transaction.stop")
                                        .append("payload", new Document("vehicleId", vin)
                                                .append("spaceId", dbSpaceId)
                                                .append("areaName", dbArea)
                                                .append("cost", costString))
                                        .append("timestamp", java.time.Instant.now().getEpochSecond());
                                localOfflineTransactions.add(0, autoStopDoc);

                                String stopPayload = String.format(
                                        "{\"vehicleId\":\"%s\",\"spaceId\":\"%s\",\"areaName\":\"%s\",\"type\":\"stop\",\"cost\":\"%s\"}",
                                        vin, dbSpaceId, dbArea, costString);
                                String stopClientIp = InetAddress.getLocalHost().getHostAddress();
                                MessageEnvelope stopEnv = MessageEnvelope.createUnsigned("transaction.stop",
                                        stopPayload, stopClientIp, UUID.randomUUID().toString()).sign(signer);
                                try {
                                    rabbitManager.withChannel((channel, node) -> {
                                        channel.basicPublish("", config.getTransactionsQueueName(), null,
                                                stopEnv.toJsonString().getBytes(StandardCharsets.UTF_8));
                                    });
                                    System.out.println("[CustomerUI] Auto-stopped previous active DB session for VIN: "
                                            + vin + " in space " + dbSpaceId + ", cost: " + costString);
                                    logger.info("Auto-stopped previous active DB session for VIN: " + vin + " in space "
                                            + dbSpaceId);
                                } catch (Exception qex) {
                                    System.out.println(
                                            "[CustomerUI] Queue offline while auto-stopping previous active DB session for VIN: "
                                                    + vin + ", space: " + dbSpaceId);
                                    logger.warn("Queue offline while auto-stopping previous DB active session for VIN: "
                                            + vin);
                                }
                            }
                        }
                    } catch (Exception ex) {
                        System.out.println("[CustomerUI] Warning checking active DB sessions: " + ex.getMessage());
                        logger.warn("Database offline or unreachable while checking active parking sessions: "
                                + ex.getMessage() + ". Proceeding with start.");
                    }
                }

                // Add new start doc to local offline history so it is immediately visible in
                // the table!
                Document startDoc = new Document("type", "transaction.start")
                        .append("payload", new Document("vehicleId", vin)
                                .append("spaceId", spaceId)
                                .append("areaName", areaName)
                                .append("cost", "-"))
                        .append("timestamp", java.time.Instant.now().getEpochSecond());
                localOfflineTransactions.add(0, startDoc);
                saveLocalTransactions(vin);

                String correlationId = UUID.randomUUID().toString(); // Tracing start
                String clientIp = InetAddress.getLocalHost().getHostAddress();
                String payload = String.format(
                        "{\"vehicleId\":\"%s\",\"spaceId\":\"%s\",\"areaName\":\"%s\",\"type\":\"start\"}", vin,
                        spaceId, areaName);
                MessageEnvelope envelope = MessageEnvelope
                        .createUnsigned("transaction.start", payload, clientIp, correlationId).sign(signer);

                boolean isOffline = false;
                try {
                    rabbitManager.withChannel((channel, node) -> {
                        channel.basicPublish("", config.getTransactionsQueueName(), null,
                                envelope.toJsonString().getBytes(StandardCharsets.UTF_8));
                        logger.info("[TRACE: " + correlationId + "] Published start message to " + node.toAddress()
                                + " (Queue declared)");
                    });
                } catch (Exception ex) {
                    logger.error("Queue server is offline. Transaction processed in Offline Mode: " + ex.getMessage());
                    isOffline = true;
                }
                return isOffline;
            }

        };

        task.setOnSucceeded(e -> {
            boolean isOffline = task.getValue();
            parkingStartTime = java.time.Instant.now();
            activeSpace = spaceId;
            Platform.runLater(() -> {
                infoCard.setVisible(true);
                infoCard.setManaged(true);
            });
            if (isOffline) {
                setStatus("🚗 Parking Started (Offline Mode)", false);
            } else {
                setStatus("🚗 Parking Started", false);
            }
        });
        task.setOnFailed(e -> {
            Throwable ex = e.getSource().getException();
            logger.error("Failed to start parking request.", ex);
            String genericMsg = "Unable to process request. Please try again later.";
            setStatus("Error: " + genericMsg, true);
        });
        new Thread(task).start();
    }

    /**
     * Handles the 'Stop Parking' action by publishing a signed message to RabbitMQ.
     */
    private void handleStop() {
        String rawVin = vinField.getText();
        String vin = rawVin == null ? "" : rawVin.trim();
        String rawSpace = spaceNumberField.getText();
        String spaceId = rawSpace == null ? "" : rawSpace.trim();

        if (vin.isEmpty() || vin.replace("-", "").trim().isEmpty()) {
            setStatus("Error: Vehicle number is required.", true);
            return;
        }

        if (spaceId.isEmpty()) {
            setStatus("Error: Space ID is required.", true);
            return;
        }
        try {
            ValidationUtils.requireValidVehicleId(vin.toUpperCase());
            ValidationUtils.requireValidSpaceId(spaceId.toUpperCase());
        } catch (IllegalArgumentException ex) {
            setStatus("Error: Invalid vehicle or parking space format.", true);
            return;
        }

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                String areaName = areaLabel != null ? areaLabel.getText() : "Unknown";
                String costString = "0.00";
                try {
                    costString = timerCostLabel.getText().replace(" NIS", "");
                } catch (Exception ignore) {
                }

                // SUC 2 Branch A: Check if there is an active parking event
                String dbActiveSpace = null;
                try {
                    List<Document> history = repository.getVehicleHistory(vin);
                    if (!history.isEmpty() && "transaction.start".equals(history.get(0).getString("type"))) {
                        dbActiveSpace = ParkingRepository.readPayloadField(history.get(0), "spaceId");
                    }
                } catch (Exception ex) {
                    logger.warn("Database offline or unreachable while verifying active parking session: " + ex.getMessage());
                }

                String resolvedActiveSpace = dbActiveSpace != null ? dbActiveSpace : activeSpace;

                if (resolvedActiveSpace == null) {
                    // Check local offline transactions
                    for (Document doc : localOfflineTransactions) {
                        String sId = ParkingRepository.readPayloadField(doc, "spaceId");
                        String type = doc.getString("type");
                        if ("transaction.start".equals(type)) {
                            resolvedActiveSpace = sId;
                            break;
                        } else if ("transaction.stop".equals(type)) {
                            break;
                        }
                    }
                }

                if (resolvedActiveSpace == null) {
                    throw new IllegalStateException("There is no open parking event for this vehicle.");
                }

                if (!spaceId.equalsIgnoreCase(resolvedActiveSpace)) {
                    throw new IllegalStateException("There is no active parking session in space " + spaceId + ". You are parked in space " + resolvedActiveSpace + ".");
                }

                // Add to local offline history so it is immediately visible in the table!
                Document stopDoc = new Document("type", "transaction.stop")
                        .append("payload", new Document("vehicleId", vin)
                                .append("spaceId", spaceNumberField.getText().trim())
                                .append("areaName", areaName)
                                .append("cost", costString))
                        .append("timestamp", java.time.Instant.now().getEpochSecond());
                localOfflineTransactions.add(0, stopDoc);
                saveLocalTransactions(vin);

                String correlationId = UUID.randomUUID().toString(); // Tracing start
                String clientIp = InetAddress.getLocalHost().getHostAddress();
                String payload = String.format(
                        "{\"vehicleId\":\"%s\",\"spaceId\":\"%s\",\"areaName\":\"%s\",\"type\":\"stop\",\"cost\":\"%s\"}",
                        vin, spaceNumberField.getText().trim(), areaName, costString);
                MessageEnvelope envelope = MessageEnvelope
                        .createUnsigned("transaction.stop", payload, clientIp, correlationId).sign(signer);

                boolean isOffline = false;
                try {
                    rabbitManager.withChannel((channel, node) -> {
                        channel.basicPublish("", config.getTransactionsQueueName(), null,
                                envelope.toJsonString().getBytes(StandardCharsets.UTF_8));
                        logger.info("[TRACE: " + correlationId + "] Published stop message to " + node.toAddress()
                                + " (Queue declared)");
                    });
                } catch (Exception ex) {
                    logger.error("Queue server is offline. Transaction processed in Offline Mode: " + ex.getMessage());
                    isOffline = true;
                }
                return isOffline;
            }

        };

        task.setOnSucceeded(e -> {
            boolean isOffline = task.getValue();
            parkingStartTime = null;
            lastActiveSpaceBeforeStop = activeSpace;
            activeSpace = null;
            if (isOffline) {
                setStatus("🛑 Parking Stopped (Offline Mode)", false);
            } else {
                setStatus("🛑 Parking Stopped", false);
            }
        });
        task.setOnFailed(e -> {
            logger.error("Failed to stop parking request.", e.getSource().getException());
            setStatus("Error: " + e.getSource().getException().getMessage(), true);
        });
        new Thread(task).start();
    }

    /**
     * Fetches and displays the parking history for the current VIN from MongoDB.
     *
     * @param showStatus whether to show status alerts/messages to the user
     */
    public void handleEvents(boolean showStatus) {
        String rawVin = vinField.getText();
        String vin = rawVin == null ? "" : rawVin.trim();
        if (vin.isEmpty()) {
            setStatus("Error: VIN required for history.", true);
            return;
        }

        System.out.println("[HISTORY-DEBUG] handleEvents called for VIN: '" + vin + "', localOfflineTransactions size: "
                + localOfflineTransactions.size());

        Task<List<Document>> task = new Task<>() {
            @Override
            protected List<Document> call() {
                List<Document> result = repository.getVehicleHistory(vin);
                System.out.println(
                        "[HISTORY-DEBUG] DB query returned " + result.size() + " documents for VIN: '" + vin + "'");
                // If DB returned 0 and we know there should be data, try once more with a fresh
                // repository
                if (result.isEmpty()) {
                    System.out.println("[HISTORY-DEBUG] DB returned 0 docs, retrying once...");
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                    result = repository.getVehicleHistory(vin);
                    System.out.println("[HISTORY-DEBUG] Retry returned " + result.size() + " documents.");
                }
                return result;
            }
        };

        task.setOnSucceeded(e -> {
            List<Document> history = task.getValue();

            // Filter local offline transactions to only include events for this VIN
            List<Document> localForVin = new java.util.ArrayList<>();
            for (Document localDoc : localOfflineTransactions) {
                String localVin = ParkingRepository.readPayloadField(localDoc, "vehicleId");
                if (vin.equals(localVin)) {
                    localForVin.add(localDoc);
                }
            }
            System.out.println("[HISTORY-DEBUG] Local offline transactions for VIN '" + vin + "': " + localForVin.size()
                    + " (total local: " + localOfflineTransactions.size() + ")");

            // Start with local events, then add non-duplicate DB events
            List<Document> merged = new java.util.ArrayList<>(localForVin);

            int dupCount = 0;
            for (Document dbDoc : history) {
                String dbType = dbDoc.getString("type");
                String dbSpace = ParkingRepository.readPayloadField(dbDoc, "spaceId");
                Long dbTime = getLongSafe(dbDoc, "timestamp");

                boolean isDup = false;
                for (Document localDoc : localForVin) {
                    String localType = localDoc.getString("type");
                    String localSpace = ParkingRepository.readPayloadField(localDoc, "spaceId");
                    Long localTime = getLongSafe(localDoc, "timestamp");

                    if (java.util.Objects.equals(localType, dbType) && java.util.Objects.equals(localSpace, dbSpace)) {
                        if (dbTime != null && localTime != null && Math.abs(dbTime - localTime) < 15) {
                            isDup = true;
                            break;
                        }
                    }
                }
                if (!isDup) {
                    merged.add(dbDoc);
                } else {
                    dupCount++;
                }
            }
            System.out.println("[HISTORY-DEBUG] Merge result: " + merged.size() + " total events (DB: " + history.size()
                    + ", local: " + localForVin.size() + ", deduped: " + dupCount + ")");

            // Sort merged list by timestamp descending
            merged.sort((d1, d2) -> {
                Long t1 = getLongSafe(d1, "timestamp");
                Long t2 = getLongSafe(d2, "timestamp");
                if (t1 == null)
                    t1 = 0L;
                if (t2 == null)
                    t2 = 0L;
                return t2.compareTo(t1);
            });

            // Print all events for debugging
            for (int i = 0; i < merged.size(); i++) {
                Document doc = merged.get(i);
                System.out.println("[HISTORY-DEBUG]   Event " + (i + 1) + ": type=" + doc.getString("type")
                        + ", space=" + ParkingRepository.readPayloadField(doc, "spaceId")
                        + ", ts=" + getLongSafe(doc, "timestamp"));
            }

            merged = aggregateHistory(merged);
            historyItems.setAll(merged);
            checkAndResumeActiveSession(merged);
            System.out.println("[HISTORY-DEBUG] historyItems now has " + historyItems.size() + " items.");

            // Prune local transactions that are now confirmed in the DB, then persist
            boolean localChanged = localOfflineTransactions.removeIf(localDoc -> {
                String localType = localDoc.getString("type");
                String localSpace = ParkingRepository.readPayloadField(localDoc, "spaceId");
                Long localTime = localDoc.getLong("timestamp");
                for (Document dbDoc : history) {
                    String dbType = dbDoc.getString("type");
                    String dbSpace = ParkingRepository.readPayloadField(dbDoc, "spaceId");
                    Long dbTime = dbDoc.getLong("timestamp");
                    if (java.util.Objects.equals(localType, dbType) && java.util.Objects.equals(localSpace, dbSpace)) {
                        if (dbTime != null && localTime != null && Math.abs(dbTime - localTime) < 15) {
                            return true; // confirmed in DB → remove from local
                        }
                    }
                }
                return false;
            });
            if (localChanged) {
                saveLocalTransactions(vin);
            }

            // SUC 3: Calculate total money owed
            double totalOwed = 0.0;
            for (Document doc : merged) {
                String costStr = ParkingRepository.readPayloadField(doc, "cost");
                totalOwed += parseCostSafe(costStr);
            }
            if (totalOwedLabel != null) {
                totalOwedLabel.setText(String.format("Total Owed: %.2f NIS", totalOwed));
                if (totalOwed > 0) {
                    totalOwedLabel.setStyle(
                            "-fx-text-fill: #dc2626; -fx-font-size: 16px; -fx-font-weight: bold; -fx-background-color: rgba(255,255,255,0.9); -fx-padding: 10 16; -fx-background-radius: 8; -fx-border-color: #dc2626; -fx-border-width: 2; -fx-border-radius: 8; -fx-effect: dropshadow(three-pass-box, rgba(220,38,38,0.3), 8, 0, 0, 2);");
                } else {
                    totalOwedLabel.setStyle(
                            "-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold; -fx-background-color: rgba(255,255,255,0.15); -fx-padding: 10 16; -fx-background-radius: 8; -fx-border-color: white; -fx-border-width: 2; -fx-border-radius: 8;");
                }
            }

            if (showStatus)
                setHistoryStatus("History updated: " + merged.size() + " events found.", false);
        });

        task.setOnFailed(e -> {
            System.out.println("[HISTORY-DEBUG] DB query FAILED with exception: " + task.getException());
            logger.error("Failed to query parking history from the database cluster.", task.getException());

            // Fallback to local offline transactions filtered by VIN
            List<Document> localForVin = new java.util.ArrayList<>();
            for (Document localDoc : localOfflineTransactions) {
                String localVin = ParkingRepository.readPayloadField(localDoc, "vehicleId");
                if (vin.equals(localVin)) {
                    localForVin.add(localDoc);
                }
            }
            List<Document> merged = new java.util.ArrayList<>(localForVin);

            merged.sort((d1, d2) -> {
                Long t1 = getLongSafe(d1, "timestamp");
                Long t2 = getLongSafe(d2, "timestamp");
                if (t1 == null)
                    t1 = 0L;
                if (t2 == null)
                    t2 = 0L;
                return t2.compareTo(t1);
            });

            merged = aggregateHistory(merged);
            historyItems.setAll(merged);
            checkAndResumeActiveSession(merged);
            System.out.println("[HISTORY-DEBUG] (Offline) historyItems now has " + historyItems.size() + " items.");

            double totalOwed = 0.0;
            for (Document doc : merged) {
                String costStr = ParkingRepository.readPayloadField(doc, "cost");
                totalOwed += parseCostSafe(costStr);
            }
            if (totalOwedLabel != null) {
                totalOwedLabel.setText(String.format("Total Owed: %.2f NIS", totalOwed));
                if (totalOwed > 0) {
                    totalOwedLabel.setStyle(
                            "-fx-text-fill: #dc2626; -fx-font-size: 16px; -fx-font-weight: bold; -fx-background-color: rgba(255,255,255,0.9); -fx-padding: 10 16; -fx-background-radius: 8; -fx-border-color: #dc2626; -fx-border-width: 2; -fx-border-radius: 8; -fx-effect: dropshadow(three-pass-box, rgba(220,38,38,0.3), 8, 0, 0, 2);");
                } else {
                    totalOwedLabel.setStyle(
                            "-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold; -fx-background-color: rgba(255,255,255,0.15); -fx-padding: 10 16; -fx-background-radius: 8; -fx-border-color: white; -fx-border-width: 2; -fx-border-radius: 8;");
                }
            }

            if (showStatus)
                setHistoryStatus("History updated (Offline): " + merged.size() + " events found.", false);
        });
        new Thread(task).start();
    }

    /**
     * Fetches the rate and zone for a specific space ID from the database.
     * 
     * @param spaceId the space identifier
     */
    private void fetchRateAndZone(String spaceId) {
        if (spaceId == null || spaceId.trim().isEmpty()) {
            Platform.runLater(() -> {
                rateLabel.setText("");
                areaLabel.setText("");
            });
            return;
        }

        if (spaceId.matches("\\d+")) {
            try {
                int num = Integer.parseInt(spaceId);
                if (num > 100) {
                    setStatus("Error: Space does not exist.", true);
                    rateLabel.setText("");
                    areaLabel.setText("");
                    return;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // Clear the specific error if it was set
        if ("Error: Space does not exist.".equals(errorLabel.getText())) {
            setStatus("", true);
        }

        // --- Optimistic UI & Local Cache lookup (Hardening R2.3-Latency-Optimization)
        // ---
        // 1. Compute instant local fallback rates & zones for 0ms visual rendering
        BigDecimal localRate = new BigDecimal("12.5");
        String localZone = "Central Zone";
        try {
            String digits = spaceId.replaceAll("[^\\d]", "");
            if (!digits.isEmpty()) {
                int id = Integer.parseInt(digits);
                if (id >= 1 && id <= 100) {
                    double[] rates = { 1.77, 70.23, 56.33, 24.36, 43.27, 35.37, 87.99, 56.22, 17.29, 55.27 };
                    String[] zones = { "Magnolia Way", "Summit Ln", "Fifth Dr", "Downing Ave", "Elm Ct", "Central Way",
                            "Queen St", "Main St", "Lansdowne Blvd", "Adams Ave" };
                    localRate = BigDecimal.valueOf(rates[(id - 1) % 10]);
                    localZone = zones[(id - 1) % 10];
                }
            }
        } catch (Exception ignored) {
        }

        // Check if we already have it cached
        Document cached = spaceCache.get(spaceId);
        if (cached != null) {
            BigDecimal rate = (BigDecimal) cached.get("rate");
            String zone = cached.getString("zone");
            rateLabel.setText(rate.toString() + " NIS/hr");
            areaLabel.setText(zone);
            return; // 0ms instant load completed!
        }

        // Render Optimistically with calculated local values immediately (0ms visual
        // slide-in)
        final BigDecimal finalLocalRate = localRate;
        final String finalLocalZone = localZone;
        rateLabel.setText(localRate.toString() + " NIS/hr");
        areaLabel.setText(localZone);

        // 2. Query MongoDB cluster in a background daemon thread to synchronize precise
        // server data
        Task<Document> task = new Task<>() {
            @Override
            protected Document call() {
                BigDecimal rate = repository.getSpaceRate(spaceId);
                String zone = repository.getSpaceZone(spaceId);
                if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) {
                    rate = finalLocalRate;
                }
                if ("Unknown".equals(zone) || zone == null || zone.isEmpty()) {
                    zone = finalLocalZone;
                }
                return new Document("rate", rate).append("zone", zone);
            }
        };

        task.setOnSucceeded(e -> {
            Document doc = task.getValue();
            BigDecimal rate = (BigDecimal) doc.get("rate");
            String zone = doc.getString("zone");

            // Cache it for future keystroke instantaneous queries
            spaceCache.put(spaceId, doc);

            // Update UI dynamically if different
            Platform.runLater(() -> {
                rateLabel.setText(rate.toString() + " NIS/hr");
                areaLabel.setText(zone);
            });
        });
        new Thread(task).start();
    }

    /**
     * Sets the status message on the UI.
     * 
     * @param message the message to display
     * @param isError true if the message indicates an error
     */
    /**
     * Sets the status message on the UI and manages the visibility of the status
     * label.
     * The status label is independent of the info card so it remains visible even
     * after
     * parking is stopped.
     *
     * @param message the message to display
     * @param isError true if the message indicates an error
     */
    private List<Document> aggregateHistory(List<Document> merged) {
        // Sort merged by timestamp ascending to pair start/stop sequentially
        List<Document> sortedAsc = new java.util.ArrayList<>(merged);
        sortedAsc.sort((d1, d2) -> {
            long t1 = getLongSafe(d1, "timestamp") != null ? getLongSafe(d1, "timestamp") : 0L;
            long t2 = getLongSafe(d2, "timestamp") != null ? getLongSafe(d2, "timestamp") : 0L;
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

        List<Document> sessions = new java.util.ArrayList<>();
        java.util.Map<String, Document> activeSessionsBySpace = new java.util.HashMap<>();

        for (Document event : sortedAsc) {
            String type = event.getString("type");
            String spaceId = ParkingRepository.readPayloadField(event, "spaceId");

            if ("transaction.start".equals(type)) {
                if (spaceId != null) {
                    // If there was already an active session in this space, push it to sessions first
                    Document previousActive = activeSessionsBySpace.remove(spaceId);
                    if (previousActive != null) {
                        Document session = new Document();
                        session.put("type", "transaction.start");
                        session.put("timestamp", previousActive.get("timestamp"));
                        session.put("startTimestamp", previousActive.get("timestamp"));
                        session.put("endTimestamp", null);
                        session.put("payload", previousActive.get("payload"));
                        sessions.add(session);
                    }
                    activeSessionsBySpace.put(spaceId, event);
                }
            } else if ("transaction.stop".equals(type)) {
                Document activeSession = null;
                if (spaceId != null) {
                    activeSession = activeSessionsBySpace.remove(spaceId);
                }
                
                if (activeSession != null) {
                    // Match with the active start session for the SAME space ID
                    Document session = new Document();
                    session.put("type", "transaction.stop");
                    session.put("timestamp", activeSession.get("timestamp"));
                    session.put("startTimestamp", activeSession.get("timestamp"));
                    session.put("endTimestamp", event.get("timestamp"));
                    
                    Document startPayload = (Document) activeSession.get("payload");
                    Document stopPayload = (Document) event.get("payload");
                    Document mergedPayload = new Document();
                    if (startPayload != null) {
                        mergedPayload.putAll(startPayload);
                    }
                    if (stopPayload != null) {
                        mergedPayload.putAll(stopPayload);
                    }
                    session.put("payload", mergedPayload);
                    
                    sessions.add(session);
                } else {
                    // Stop without a preceding start event in this space
                    Document session = new Document();
                    session.put("type", "transaction.stop");
                    session.put("timestamp", event.get("timestamp"));
                    session.put("startTimestamp", null);
                    session.put("endTimestamp", event.get("timestamp"));
                    session.put("payload", event.get("payload"));
                    sessions.add(session);
                }
            } else {
                // Unknown event type, just add it as is
                sessions.add(event);
            }
        }

        // Add all remaining uncompleted active sessions from the map to the sessions list
        for (Document activeSession : activeSessionsBySpace.values()) {
            Document session = new Document();
            session.put("type", "transaction.start");
            session.put("timestamp", activeSession.get("timestamp"));
            session.put("startTimestamp", activeSession.get("timestamp"));
            session.put("endTimestamp", null);
            session.put("payload", activeSession.get("payload"));
            sessions.add(session);
        }

        // Sort descending by start timestamp so newest is on top
        sessions.sort((s1, s2) -> {
            Long t1 = getLongSafe(s1, "startTimestamp");
            if (t1 == null) t1 = getLongSafe(s1, "timestamp");
            Long t2 = getLongSafe(s2, "startTimestamp");
            if (t2 == null) t2 = getLongSafe(s2, "timestamp");
            if (t1 == null) t1 = 0L;
            if (t2 == null) t2 = 0L;
            return t2.compareTo(t1);
        });

        return sessions;
    }

    private void checkAndResumeActiveSession(List<Document> merged) {
        if (merged == null || merged.isEmpty()) {
            return;
        }
        Document newestSession = merged.get(0);
        String type = newestSession.getString("type");
        Long startTimestamp = getLongSafe(newestSession, "startTimestamp");
        Long endTimestamp = getLongSafe(newestSession, "endTimestamp");

        if ("transaction.start".equals(type) && startTimestamp != null && endTimestamp == null) {
            String spaceId = ParkingRepository.readPayloadField(newestSession, "spaceId");
            if (spaceId != null && !spaceId.isEmpty()) {
                activeSpace = spaceId;
                parkingStartTime = java.time.Instant.ofEpochSecond(startTimestamp);
                Platform.runLater(() -> {
                    if (spaceNumberField != null) {
                        spaceNumberField.setText(spaceId);
                    }
                    if (infoCard != null) {
                        infoCard.setVisible(true);
                        infoCard.setManaged(true);
                    }
                });
            }
        }
    }

    private void setHistoryStatus(String message, boolean isError) {
        if (historyStatusLabel != null) {
            Platform.runLater(() -> {
                if (isError) {
                    historyStatusLabel.setStyle("-fx-text-fill: #991b1b; -fx-font-weight: bold;");
                } else {
                    historyStatusLabel.setStyle(null);
                    historyStatusLabel.getStyleClass().add("status-label-ok");
                }
                historyStatusLabel.setText(message);
                historyStatusLabel.setVisible(true);
                historyStatusLabel.setManaged(true);

                if (historyStatusTimer != null) {
                    historyStatusTimer.stop();
                }
                historyStatusTimer = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(5));
                historyStatusTimer.setOnFinished(e -> {
                    historyStatusLabel.setVisible(false);
                    historyStatusLabel.setManaged(false);
                });
                historyStatusTimer.play();
            });
        }
    }

    private void setStatus(String message, boolean isError) {
        Platform.runLater(() -> {
            if (isError) {
                errorLabel.setText(message);
                // Hide the status label when an error is shown
                statusLabel.setText("");
                statusLabel.setVisible(false);
                statusLabel.setManaged(false);
            } else {
                errorLabel.setText("");
                statusLabel.setText(message);
                statusLabel.setVisible(true);
                statusLabel.setManaged(true);

                // Dynamic styling based on status message content (Hardening
                // R1-Aesthetic-Premium)
                statusLabel.setStyle(null);
                statusLabel.getStyleClass().removeAll("status-label-error", "status-label-ok");
                if (message.contains("Started")) {
                    statusLabel.getStyleClass().add("status-label-ok");
                } else if (message.contains("Stopped")) {
                    statusLabel.getStyleClass().add("status-label-ok");
                }
            }

            if (message != null && !message.isEmpty()) {
                if (dashboardStatusTimer != null) {
                    dashboardStatusTimer.stop();
                }
                dashboardStatusTimer = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(5));
                dashboardStatusTimer.setOnFinished(e -> {
                    if (errorLabel != null)
                        errorLabel.setText("");
                    if (statusLabel != null) {
                        statusLabel.setText("");
                        statusLabel.setVisible(false);
                        statusLabel.setManaged(false);
                    }
                });
                dashboardStatusTimer.play();
            }
        });
    }

    private static Long getLongSafe(Document doc, String key) {
        if (doc == null || !doc.containsKey(key))
            return null;
        Object val = doc.get(key);
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        return null;
    }

    private static double parseCostSafe(String costStr) {
        if (costStr == null || costStr.isEmpty() || costStr.equals("null") || costStr.equals("-")) {
            return 0.0;
        }
        try {
            String clean = costStr.replaceAll("[^0-9.]", "").trim();
            if (clean.isEmpty()) {
                return 0.0;
            }
            return Double.parseDouble(clean);
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private Label requestLabel;
    private Label resultLabel;

    /**
     * Attaches the recommendation panel controls.
     *
     * @param requestLabel the recommendation request output label
     * @param resultLabel the recommendation result output label
     */
    public void attachRecommender(Label requestLabel, Label resultLabel) {
        this.requestLabel = requestLabel;
        this.resultLabel = resultLabel;
    }

    private String formatRecommendationResult(String rawResult) {
        if (rawResult == null || rawResult.isBlank() || "NONE".equalsIgnoreCase(rawResult)) {
            return rawResult;
        }
        // Example rawResult: "Space 3;0" or "Space 3;0, Space 13;0"
        String[] recommendationParts = rawResult.split(", ");
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < recommendationParts.length; i++) {
            String part = recommendationParts[i].replace("Space ", "").trim(); // e.g. "3;0"
            String[] spaceAndCitations = part.split(";");
            if (spaceAndCitations.length >= 2) {
                formatted.append("Space ").append(spaceAndCitations[0])
                         .append(" (").append(spaceAndCitations[1]).append(" Citations)");
            } else {
                formatted.append(recommendationParts[i]);
            }
            if (i < recommendationParts.length - 1) {
                formatted.append(", ");
            }
        }
        return formatted.toString();
    }

    /**
     * Validates the current space input and starts an explicit recommendation query.
     *
     * @param none no input parameters
     * @return no return value
     */
    private void handleRecommendParking() {
        String spaceId = spaceNumberField == null || spaceNumberField.getText() == null
                ? "" : spaceNumberField.getText().trim();
        if (spaceId.isEmpty()) {
            setStatus("Error: Parking space number is required.", true);
            requestLabel.setText("-");
            resultLabel.setText("-");
            return;
        }
        if (!spaceId.matches("\\d+")) {
            setStatus("Error: Parking space number must be numeric.", true);
            requestLabel.setText("-");
            resultLabel.setText("-");
            return;
        }
        try {
            int numericSpace = Integer.parseInt(spaceId);
            if (numericSpace < 1 || numericSpace > 100) {
                setStatus("Error: Parking space number must be between 1 and 100.", true);
                requestLabel.setText("-");
                resultLabel.setText("-");
                return;
            }
        } catch (NumberFormatException ex) {
            setStatus("Error: Parking space number must be numeric.", true);
            requestLabel.setText("-");
            resultLabel.setText("-");
            return;
        }
        fetchRecommendation(spaceId);
    }

    /**
     * Sends a signed TLS recommendation request to one recommender node.
     *
     * @param spaceId validated numeric parking space number
     * @return no return value
     */
    private void fetchRecommendation(String spaceId) {
        try {
            ValidationUtils.requireValidSpaceId(spaceId.trim());
        } catch (IllegalArgumentException ex) {
            setStatus("Error: Invalid parking space number.", true);
            return;
        }

        List<ClusterNode> recNodes = new java.util.ArrayList<>(config.getRecommenderNodes());
        java.util.Collections.shuffle(recNodes);

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                JsonObject request = RecommenderRequestSigner.createSignedRequest(
                        "CLIENT_QUERY",
                        spaceId.trim(),
                        UUID.randomUUID().toString(),
                        "customer-ui",
                        signer);

                javax.net.ssl.SSLContext sslContext = TlsUtils.createSslContext(
                        config.getTlsTruststorePath(),
                        config.getTlsTruststorePassword(),
                        config.getTlsKeystorePath(),
                        config.getTlsKeystorePassword());

                Exception lastEx = null;
                for (ClusterNode node : recNodes) {
                    try (SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket()) {
                        socket.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                        socket.connect(new java.net.InetSocketAddress(node.getHost(), node.getPort()), 3000);
                        socket.startHandshake();
                        try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                            
                            writer.println(request.toString());
                            String responseLine = reader.readLine();
                            if (responseLine == null) {
                                throw new IOException("Empty response from recommender node " + node.getDisplayName());
                            }
                            return responseLine;
                        }
                    } catch (Exception ex) {
                        logger.warn("Failed to query recommender node " + node.getDisplayName() + " at " + node.toAddress() + ": " + ex.getMessage());
                        lastEx = ex;
                    }
                }
                if (lastEx != null) {
                    throw lastEx;
                }
                throw new IOException("No configured recommender nodes could be reached.");
            }
        };

        task.setOnSucceeded(e -> {
            try {
                JsonObject response = JsonParser.parseString(task.getValue()).getAsJsonObject();
                String status = response.get("status").getAsString();
                if ("SUCCESS".equalsIgnoreCase(status)) {
                    String result = response.get("result").getAsString();
                    String[] parts = result.split("\n");
                    if (parts.length >= 2) {
                        requestLabel.setText(parts[0].replace("Request:", "").trim());
                        String rawResult = parts[1].replace("Result:", "").trim();
                        resultLabel.setText(formatRecommendationResult(rawResult));
                    }
                } else {
                    String reason = response.has("reason") ? response.get("reason").getAsString() : "Consensus failed.";
                    requestLabel.setText("Failed");
                    resultLabel.setText(reason);
                }
            } catch (Exception ex) {
                requestLabel.setText("Failed");
                resultLabel.setText("Invalid response");
            }
        });

        task.setOnFailed(e -> {
            if (task.getException() != null) {
                logger.error("Recommender fetch task failed", task.getException());
            }
            requestLabel.setText("Failed");
            resultLabel.setText("Node unreachable");
        });

        new Thread(task).start();
    }
}
