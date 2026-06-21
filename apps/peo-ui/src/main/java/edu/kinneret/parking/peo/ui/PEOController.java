package edu.kinneret.parking.peo.ui;

import edu.kinneret.parking.common.*;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates the PEO user interface by validating user actions, querying the
 * repository, and publishing secured citation requests to the queue cluster.
 */
public class PEOController {

    /**
     * Represents one action/result pair shown in the local activity log table.
     */
    public static class ActivityLogEntry {
        private final String action;
        private final String vin;
        private final String result;

        /**
         * Creates one activity entry for display in the UI log.
         *
         * @param action the action that was performed
         * @param vin the vehicle VIN
         * @param result the outcome shown to the user
         */
        public ActivityLogEntry(String action, String vin, String result) {
            this.action = action;
            this.vin = vin;
            this.result = result;
        }

        /**
         * Returns the action that was performed.
         *
         * @return the recorded action name
         */
        public String getAction() { return action; }

        /**
         * Returns the VIN.
         *
         * @return the recorded VIN
         */
        public String getVin() { return vin; }

        /**
         * Returns the result associated with the action.
         *
         * @return the recorded result text
         */
        public String getResult() { return result; }
    }

    private final AppConfig config;
    private final ParkingRepository repository;
    private final RabbitMqConnectionManager rabbitManager;
    private final SecureMessageSigner signer;

    private TextField vinField;
    private TextField spaceNumberField;
    private TextField enforcerIdField;
    private TextField citationCostField;
    private TextField citationReasonField;
    private Label alertLabel;
    private VBox citationSection;
    private TableView<ActivityLogEntry> activityTable;

    private final ObservableList<ActivityLogEntry> activityItems = FXCollections.observableArrayList();
    private final Set<String> observedParkingMessageIds = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new PEOController with cluster-aware infrastructure.
     * @param config the application configuration
     * @param repository the MongoDB repository
     * @param rabbitManager the RabbitMQ connection manager
     */
    public PEOController(AppConfig config, ParkingRepository repository, RabbitMqConnectionManager rabbitManager) {
        this.config = config;
        this.repository = repository;
        this.rabbitManager = rabbitManager;
        this.signer = new SecureMessageSigner(config.getHmacSecret());
    }

    /**
     * Attaches UI components to the controller.
     * @param vin the VIN field
     * @param space the space ID field
     * @param enforcerId the enforcer ID field
     * @param cost the citation cost field
     * @param reason the citation reason field
     * @param alert the status alert label
     * @param section the citation input section
     * @param table the activity log table
     */
    public void attach(TextField vin, TextField space, TextField enforcerId, TextField cost, TextField reason, Label alert, VBox section, TableView<ActivityLogEntry> table) {
        this.vinField = vin;
        this.spaceNumberField = space;
        this.enforcerIdField = enforcerId;
        this.citationCostField = cost;
        this.citationReasonField = reason;
        this.alertLabel = alert;
        this.citationSection = section;
        this.activityTable = table;
        this.activityTable.setItems(activityItems);
        startParkingEventMonitor();
    }

    private void startParkingEventMonitor() {
        Thread monitor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<org.bson.Document> events = repository.getAllTransactions();
                    for (int index = events.size() - 1; index >= 0; index--) {
                        org.bson.Document event = events.get(index);
                        String id = event.getString("messageId");
                        if (id == null) {
                            id = String.valueOf(event.getObjectId("_id"));
                        }
                        if (!observedParkingMessageIds.add(id)) {
                            continue;
                        }
                        String type = event.getString("type");
                        if (!"transaction.start".equals(type) && !"transaction.stop".equals(type)) {
                            continue;
                        }
                        String vehicle = ParkingRepository.readPayloadField(event, "vehicleId");
                        String space = ParkingRepository.readPayloadField(event, "spaceId");
                        String action = "transaction.start".equals(type) ? "PARKING START" : "PARKING STOP";
                        String result = "Space " + space;
                        Platform.runLater(() -> {
                            activityItems.add(0, new ActivityLogEntry(action, vehicle, result));
                            if ("PARKING START".equals(action)) {
                                setOutput("New parking event: vehicle " + vehicle + " in space " + space, false);
                            }
                        });
                    }
                    Thread.sleep(500L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception exception) {
                    try {
                        Thread.sleep(1_000L);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "peo-parking-event-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    /**
     * Attaches action buttons to the controller.
     * @param check the legality check button
     * @param cite the citation issuance button
     */
    public void attachButtons(Button check, Button cite) {
        check.setOnAction(e -> handleCheck());
        cite.setOnAction(e -> handleIssueCitation());
    }

    /**
     * Handles the 'Check Legality' action by querying the database cluster.
     */
    private void handleCheck() {
        String vin = vinField.getText().trim();
        String space = spaceNumberField.getText().trim();

        // Reset field error styles
        vinField.setStyle("");
        spaceNumberField.setStyle("");

        if (vin.isEmpty()) {
            vinField.setStyle("-fx-border-color: #ef4444; -fx-border-width: 2px;");
            setOutput("Please enter a vehicle VIN.", true);
            return;
        }
        if (space.isEmpty()) {
            spaceNumberField.setStyle("-fx-border-color: #ef4444; -fx-border-width: 2px;");
            setOutput("Please enter a parking space number.", true);
            return;
        }
        final String safeCheckVin;
        final String safeCheckSpace;
        try {
            safeCheckVin = ValidationUtils.requireValidVehicleId(vin.toUpperCase());
            safeCheckSpace = ValidationUtils.requireValidSpaceId(space.toUpperCase());
        } catch (IllegalArgumentException ex) {
            setOutput("Please enter a valid vehicle and parking space.", true);
            return;
        }

        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                return repository.checkLegality(safeCheckVin, safeCheckSpace);
            }
        };

        task.setOnSucceeded(e -> {
            String result = task.getValue();

            // Hide citation section by default
            citationSection.setVisible(false);
            citationSection.setManaged(false);

            // Reset field styles
            vinField.setStyle("");
            spaceNumberField.setStyle("");

            if ("UNKNOWN_VIN".equals(result)) {
                vinField.setStyle("-fx-border-color: #ef4444; -fx-border-width: 2px;");
                setOutput("❌  Vehicle number is not correct.", true);
                appendActivity("CHECK", safeCheckVin, "Unknown VIN");
                return;
            }

            if ("UNKNOWN_SPACE".equals(result)) {
                spaceNumberField.setStyle("-fx-border-color: #ef4444; -fx-border-width: 2px;");
                setOutput("❌  Parking space is not correct.", true);
                appendActivity("CHECK", safeCheckVin, "Unknown Space");
                return;
            }

            // Valid VIN and space — log the query
            Task<Void> logTask = new Task<>() {
                @Override
                protected Void call() {
                    org.bson.Document queryLog = new org.bson.Document("timestamp", java.time.Instant.now().toString())
                            .append("vehicleId", safeCheckVin)
                            .append("spaceId", safeCheckSpace)
                            .append("response", result);
                    repository.logSystemQuery(queryLog);
                    return null;
                }
            };
            new Thread(logTask).start();

            boolean isIllegal = "Parking Not Ok".equalsIgnoreCase(result);
            if (isIllegal) {
                setOutput("⚠️  Parking Not Ok — issue a citation.", true);
            } else {
                setOutput("✅  Parking Ok", false);
            }
            appendActivity("CHECK", safeCheckVin, result);

            // Show citation section (with Officer ID) only when parking is NOT ok
            citationSection.setVisible(isIllegal);
            citationSection.setManaged(isIllegal);
        });

        task.setOnFailed(e -> setOutput("Error: Could not connect to the database.", true));
        new Thread(task).start();
    }

    /**
     * Handles the 'Issue Citation' action by publishing a signed message to RabbitMQ.
     */
    private void handleIssueCitation() {
        String vin = vinField.getText().trim();
        String space = spaceNumberField.getText().trim();
        String enforcer = enforcerIdField.getText().trim();
        String cost = citationCostField.getText().trim();
        String reason = citationReasonField.getText().trim();

        // Reset styling
        enforcerIdField.setStyle("");

        if (vin.isEmpty() || space.isEmpty() || cost.isEmpty() || reason.isEmpty()) return;

        if (enforcer.isEmpty()) {
            enforcerIdField.setStyle("-fx-effect: dropshadow(three-pass-box, rgba(239,68,68,0.8), 10, 0, 0, 0); -fx-border-color: #ef4444; -fx-border-width: 2px;");
            return;
        }
        try {
            vin = ValidationUtils.requireValidVehicleId(vin.toUpperCase());
            space = ValidationUtils.requireValidSpaceId(space.toUpperCase());
            double amount = Double.parseDouble(cost);
            ValidationUtils.requireAmountInRange(amount, "amount", config.getMaxAllowedAmount());
            reason = ValidationUtils.requireValidReason(reason);
        } catch (Exception ex) {
            setOutput("Please enter valid citation details.", true);
            return;
        }

        final String safeVin = vin;
        final String safeSpace = space;
        final double safeCost = Double.parseDouble(cost);
        final String safeReason = reason;
        final String safeEnforcer = enforcer;

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                String correlationId = UUID.randomUUID().toString();
                String clientIp = InetAddress.getLocalHost().getHostAddress();
                JsonObject json = new JsonObject();
                json.addProperty("vehicleId", safeVin);
                json.addProperty("spaceId", safeSpace);
                json.addProperty("amount", safeCost);
                json.addProperty("reason", safeReason);
                if (safeEnforcer != null && !safeEnforcer.isEmpty()) {
                    json.addProperty("officer", safeEnforcer);
                }
                String payload = json.toString();
                ValidationUtils.validateParkingPayload(payload, config.getMaxAllowedAmount(), "citation.issue");
                MessageEnvelope envelope = MessageEnvelope.createUnsigned("citation.issue", payload, clientIp, correlationId).sign(signer);
                
                rabbitManager.withPublisherConfirms((channel, node) -> {
                    channel.basicPublish("", config.getCitationsQueueName(), null, envelope.toJsonString().getBytes(StandardCharsets.UTF_8));
                });
                return null;
            }
        };


        task.setOnSucceeded(e -> {
            setOutput("Citation Issued to Cluster", false);
            appendActivity("CITATION", safeVin, "Issued");
            citationSection.setVisible(false);
            citationSection.setManaged(false);
        });
        task.setOnFailed(e -> {
            Throwable failure = task.getException();
            String failureReason = failure == null || failure.getMessage() == null
                    ? "Service temporarily unavailable."
                    : failure.getMessage();
            setOutput("Error: " + failureReason, true);
        });
        new Thread(task).start();
    }

    /**
     * Updates the UI alert label.
     * @param text the message
     * @param error true if it's an error
     */
    private void setOutput(String text, boolean error) {
        alertLabel.setText(text);
        alertLabel.setStyle(error ? "-fx-text-fill: #ef4444;" : "-fx-text-fill: #10b981;");
    }

    /**
     * Appends an entry to the local activity table.
     * @param action the action performed
     * @param vin the VIN
     * @param result the result of the action
     */
    private void appendActivity(String action, String vin, String result) {
        Platform.runLater(() -> activityItems.add(0, new ActivityLogEntry(action, vin, result)));
    }
}
