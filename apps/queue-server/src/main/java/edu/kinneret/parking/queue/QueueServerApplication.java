package edu.kinneret.parking.queue;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.RabbitMqConnectionManager;
import edu.kinneret.parking.common.SecurityLogger;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main entry point for the queue-server foundation.
 */
public final class QueueServerApplication {
    private static final Logger logger = Logger.getLogger(QueueServerApplication.class.getName());
    static final String PERSISTENCE_OWNER = "storage-server";

/**

 * Constructs a new QueueServerApplication.

 */

    private QueueServerApplication() {
    }

    /**
     * Starts the queue-server foundation and initializes the RabbitMQ topology.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        AppConfig appConfig = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.QUEUE_SERVER);
        SecurityLogger.initialize(System.getenv().getOrDefault("SECURITY_LOG_PATH", "logs/security.log"));
        try {
            System.out.println("Starting queue-server with config: " + appConfig.toRedactedSummary());

            RabbitMqConnectionManager connectionManager = new RabbitMqConnectionManager(appConfig);
            QueueHealthChecker healthChecker = new QueueHealthChecker(connectionManager);
            RabbitMqTopologyInitializer topologyInitializer = new RabbitMqTopologyInitializer(appConfig, connectionManager);
            healthChecker.requireHealthyNode();
            topologyInitializer.initialize();
            waitForStorageConsumers(connectionManager, appConfig);
            System.out.println("Queue server topology initialization completed successfully. "
                    + "Queue consumption and MongoDB persistence are owned exclusively by "
                    + PERSISTENCE_OWNER + ".");
            System.out.println("Queue Server is running. Press Ctrl+C to stop it.");

            CountDownLatch shutdownLatch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(
                    shutdownLatch::countDown,
                    "queue-server-shutdown"));
            try {
                shutdownLatch.await();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                logger.info("Queue Server shutdown requested.");
            }
        } catch (Exception e) {
            SecurityLogger.logSecurityEvent("Queue server startup failed: " + e.toString());
            logger.log(Level.SEVERE, "Queue server startup failed", e);
            System.err.println("Queue server startup failed. See server logs for details.");
            System.exit(1);
        }
    }

    private static void waitForStorageConsumers(
            RabbitMqConnectionManager connectionManager,
            AppConfig appConfig) throws InterruptedException {
        final int maximumAttempts = 60;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            boolean[] consumersReady = {false};
            try {
                connectionManager.withChannel((channel, node) -> {
                    long transactionConsumers = channel.consumerCount(appConfig.getTransactionsQueueName());
                    long citationConsumers = channel.consumerCount(appConfig.getCitationsQueueName());
                    consumersReady[0] = transactionConsumers > 0 && citationConsumers > 0;
                });
            } catch (RuntimeException exception) {
                logger.log(Level.FINE, "Storage consumer readiness check failed", exception);
            }
            if (consumersReady[0]) {
                System.out.println("Storage Server consumers are connected; MongoDB persistence is active.");
                return;
            }
            System.out.println("Waiting for Storage Server consumers... attempt "
                    + attempt + " of " + maximumAttempts);
            Thread.sleep(2_000L);
        }
        throw new IllegalStateException(
                "Storage Server did not connect to both RabbitMQ queues within 120 seconds.");
    }
}
