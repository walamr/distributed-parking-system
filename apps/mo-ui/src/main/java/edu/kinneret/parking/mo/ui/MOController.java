package edu.kinneret.parking.mo.ui;

import edu.kinneret.parking.common.ParkingRepository;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import org.bson.Document;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller for the Municipality Office (MO) UI.
 * Handles fetching and displaying cluster-wide reports.
 */
public class MOController {
    private final ParkingRepository repository;
    private final edu.kinneret.parking.common.RabbitMqConnectionManager rabbitManager;

    private TableView<Document> transactionsTable;
    private TableView<Document> citationsTable;
    private Label statusLabel;
    private Label tableHeader;
    private Label mongoStatusLabel;
    private Label rabbitStatusLabel;

    private final ObservableList<Document> transactionItems = FXCollections.observableArrayList();
    private final ObservableList<Document> citationItems = FXCollections.observableArrayList();
    private final AtomicBoolean transactionRefreshInProgress = new AtomicBoolean(false);
    private final AtomicBoolean citationRefreshInProgress = new AtomicBoolean(false);
    private volatile boolean citationBaselineLoaded;

    /**
     * Creates a new controller with the specified repository.
     * @param repository the parking data repository
     * @param config the application configuration
     */
    public MOController(ParkingRepository repository, edu.kinneret.parking.common.AppConfig config) {
        this.repository = repository;
        this.rabbitManager = new edu.kinneret.parking.common.RabbitMqConnectionManager(config);
    }

    /**
     * Attaches the UI components to the controller.
     * @param transactionsTable the table for displaying transactions
     * @param citationsTable the table for displaying citations
     * @param statusLabel the label for displaying status messages
     * @param tableHeader the header label for the report
     */
    public void attach(TableView<Document> transactionsTable, TableView<Document> citationsTable, Label statusLabel, Label tableHeader) {
        this.transactionsTable = transactionsTable;
        this.citationsTable = citationsTable;
        this.statusLabel = statusLabel;
        this.tableHeader = tableHeader;

        this.transactionsTable.setItems(transactionItems);
        this.citationsTable.setItems(citationItems);
    }

    /**
     * Attaches health labels to the controller.
     * @param mongoLabel the label for MongoDB status
     * @param rabbitLabel the label for RabbitMQ status
     */
    public void attachHealth(Label mongoLabel, Label rabbitLabel) {
        this.mongoStatusLabel = mongoLabel;
        this.rabbitStatusLabel = rabbitLabel;
        startHealthMonitor();
    }

    /**
     * Starts a daemon thread that refreshes the health labels every five seconds.
     */
    private void startHealthMonitor() {
        Thread monitorThread = new Thread(() -> {
            while (true) {
                refreshHealth();
                try {
                    Thread.sleep(5000); // Refresh every 5 seconds
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    /**
     * Queries MongoDB replica-set status and RabbitMQ connectivity, then updates
     * the health labels on the JavaFX Application Thread.
     */
    private void refreshHealth() {
        Document mongoStatus = repository.getClusterStatus();
        boolean rabbitHealthy = rabbitManager.checkHealth();

        Platform.runLater(() -> {
            if (mongoStatus != null) {
                List<?> members = (List<?>) mongoStatus.get("members");
                int ok = 0;
                String primaryNode = "Unknown";
                if (members != null) {
                    for (Object m : members) {
                        if (m instanceof Document d) {
                            Object healthObj = d.get("health");
                            if (healthObj instanceof Number n && n.intValue() == 1) ok++;
                            if ("PRIMARY".equals(d.getString("stateStr"))) {
                                primaryNode = d.getString("name");
                            }
                        }
                    }
                }
                mongoStatusLabel.setText(String.format("🟢 [rs0] HEALTHY (%d/3 Nodes) | PRIMARY: %s", ok, primaryNode));
                mongoStatusLabel.setStyle("-fx-text-fill: #4ade80; -fx-font-weight: bold;");
            } else {
                mongoStatusLabel.setText("🔴 [rs0] UNREACHABLE");
                mongoStatusLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold;");
            }

            if (rabbitHealthy) {
                rabbitStatusLabel.setText("🟢 [mulligan-cluster] ACTIVE (Quorum OK)");
                rabbitStatusLabel.setStyle("-fx-text-fill: #4ade80; -fx-font-weight: bold;");
            } else {
                rabbitStatusLabel.setText("🔴 [mulligan-cluster] DOWN");
                rabbitStatusLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold;");
            }
        });
    }

    /**
     * Attaches the action buttons to the controller.
     * @param txBtn the transaction report button
     * @param ctBtn the citation report button
     */
    public void attachButtons(Button txBtn, Button ctBtn) {
        txBtn.setOnAction(e -> refreshTransactions(true));
        ctBtn.setOnAction(e -> refreshCitations(true));
        startTransactionAutoRefresh();
        refreshTransactions(true);
        refreshCitations(false);
    }

    /**
     * Starts a daemon thread that periodically refreshes the currently visible
     * transaction report and the citation report once per second.
     */
    private void startTransactionAutoRefresh() {
        Thread refreshThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1_000L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Platform.runLater(() -> {
                    String header = tableHeader == null ? "" : tableHeader.getText();
                    if (transactionsTable != null && transactionsTable.isVisible()
                            && header != null && header.startsWith("TRANSACTION REPORT")) {
                        refreshTransactions(false);
                    }
                    refreshCitations(false);
                });
            }
        }, "municipality-transaction-refresh");
        refreshThread.setDaemon(true);
        refreshThread.start();
    }

    /**
     * Handles the transaction report request by fetching and consolidating
     * transaction events on a background thread and updating the table.
     *
     * @param announceRefresh true to display status messages for this refresh,
     *                        false for a silent background refresh
     */
    private void refreshTransactions(boolean announceRefresh) {
        if (!transactionRefreshInProgress.compareAndSet(false, true)) {
            return;
        }
        if (announceRefresh) {
            setStatus("Fetching transactions from cluster...", false);
        }
        Task<List<Document>> task = new Task<>() {
            /**
             * Fetches and consolidates the parking transactions report from the repository.
             *
             * @return the list of consolidated transaction documents
             */
            @Override
            protected List<Document> call() {
                List<Document> rawEvents = repository.getAllTransactions();
                rawEvents.sort((d1, d2) -> {
                    long t1 = d1.get("timestamp") != null ? ((Number) d1.get("timestamp")).longValue() : 0L;
                    long t2 = d2.get("timestamp") != null ? ((Number) d2.get("timestamp")).longValue() : 0L;
                    if (t1 != t2) {
                        return Long.compare(t1, t2);
                    }
                    long stored1 = d1.get("storedAt") instanceof Number n1 ? n1.longValue() : 0L;
                    long stored2 = d2.get("storedAt") instanceof Number n2 ? n2.longValue() : 0L;
                    if (stored1 != stored2) {
                        return Long.compare(stored1, stored2);
                    }
                    String type1 = d1.getString("type");
                    String type2 = d2.getString("type");
                    boolean isStop1 = type1 != null && type1.endsWith(".stop");
                    boolean isStop2 = type2 != null && type2.endsWith(".stop");
                    if (isStop1 && !isStop2) {
                        return 1; // start must be processed before stop
                    }
                    if (!isStop1 && isStop2) {
                        return -1;
                    }
                    return 0;
                });
                
                java.util.Map<String, Document> activeSessions = new java.util.HashMap<>();
                List<Document> consolidated = new java.util.ArrayList<>();
                
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
                            session.put("areaName", ParkingRepository.readPayloadField(event, "areaName"));
                            session.put("startTime", startEvent.getLong("timestamp"));
                            session.put("stopTime", event.getLong("timestamp"));
                            session.put("cost", ParkingRepository.readPayloadField(event, "cost"));
                            consolidated.add(session);
                        }
                    }
                }
                
                for (Document startEvent : activeSessions.values()) {
                    Document session = new Document();
                    session.put("vehicleId", ParkingRepository.readPayloadField(startEvent, "vehicleId"));
                    session.put("spaceId", ParkingRepository.readPayloadField(startEvent, "spaceId"));
                    session.put("areaName", ParkingRepository.readPayloadField(startEvent, "areaName"));
                    session.put("startTime", startEvent.getLong("timestamp"));
                    session.put("stopTime", null);
                    session.put("cost", null);
                    consolidated.add(session);
                }
                
                consolidated.sort((d1, d2) -> Long.compare(d2.getLong("startTime"), d1.getLong("startTime")));
                return consolidated;
            }
        };
        task.setOnSucceeded(e -> {
            transactionRefreshInProgress.set(false);
            transactionItems.setAll(task.getValue());
            showTable(true);
            tableHeader.setText("TRANSACTION REPORT (" + transactionItems.size() + ")");
            if (announceRefresh) {
                setStatus("Success - automatic refresh is active", false);
            }
        });
        task.setOnFailed(e -> {
            transactionRefreshInProgress.set(false);
            if (announceRefresh) {
                setStatus("Error: Failed to retrieve transaction report from cluster.", true);
            }
        });
        task.setOnCancelled(e -> transactionRefreshInProgress.set(false));
        new Thread(task).start();
    }

    /**
     * Handles the citation report request by fetching citations on a background
     * thread, enriching them with zone information, and updating the table.
     *
     * @param announceRefresh true to display status messages for this refresh,
     *                        false for a silent background refresh
     */
    private void refreshCitations(boolean announceRefresh) {
        if (!citationRefreshInProgress.compareAndSet(false, true)) {
            return;
        }
        if (announceRefresh) {
            setStatus("Fetching citations from cluster...", false);
        }
        Task<List<Document>> task = new Task<>() {
            /**
             * Fetches the complete list of citations from the repository.
             *
             * @return the list of citation documents
             */
            @Override
            protected List<Document> call() {
                List<Document> citations = repository.getAllCitations();
                for (Document citation : citations) {
                    String spaceId = ParkingRepository.readPayloadField(citation, "spaceId");
                    String zone = repository.getSpaceZone(spaceId);
                    Document payloadDoc = ParkingRepository.readPayloadDocument(citation);
                    if (payloadDoc != null) {
                        payloadDoc.put("areaName", zone);
                        citation.put("payload", payloadDoc);
                    }
                }
                return citations;
            }
        };
        task.setOnSucceeded(e -> {
            citationRefreshInProgress.set(false);
            int previousCount = citationItems.size();
            citationItems.setAll(task.getValue());
            boolean newCitationArrived = citationBaselineLoaded && citationItems.size() > previousCount;
            citationBaselineLoaded = true;
            if (announceRefresh || newCitationArrived || citationsTable.isVisible()) {
                showTable(false);
                tableHeader.setText("CITATION REPORT (" + citationItems.size() + ")");
            }
            if (announceRefresh) {
                setStatus("Success - automatic citation refresh is active", false);
            } else if (newCitationArrived) {
                setStatus("New citation received automatically", false);
            }
        });
        task.setOnFailed(e -> {
            citationRefreshInProgress.set(false);
            if (announceRefresh) {
                setStatus("Error: Failed to retrieve citation report from cluster.", true);
            }
        });
        task.setOnCancelled(e -> citationRefreshInProgress.set(false));
        new Thread(task).start();
    }

    /**
     * Toggles the visibility of the tables.
     * @param showTx true to show transactions, false to show citations
     */
    private void showTable(boolean showTx) {
        transactionsTable.setVisible(showTx);
        transactionsTable.setManaged(showTx);
        citationsTable.setVisible(!showTx);
        citationsTable.setManaged(!showTx);
    }

    /**
     * Sets the status label text and style.
     * @param text the status message
     * @param error true if the message is an error
     */
    private void setStatus(String text, boolean error) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            statusLabel.setStyle(error ? "-fx-text-fill: #ef4444;" : "-fx-text-fill: #10b981;");
        });
    }
}
