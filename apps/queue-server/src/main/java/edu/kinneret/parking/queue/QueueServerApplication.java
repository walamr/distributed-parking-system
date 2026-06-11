package edu.kinneret.parking.queue;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.RabbitMqConnectionManager;
import edu.kinneret.parking.common.SecurityLogger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main entry point for the queue-server foundation.
 */
public final class QueueServerApplication {
    private static final Logger logger = Logger.getLogger(QueueServerApplication.class.getName());

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
            QueueConsumerService consumerService = new QueueConsumerService(appConfig, connectionManager);

            healthChecker.requireHealthyNode();
            topologyInitializer.initialize();
            System.out.println("Queue server startup completed successfully. Starting secured consumers.");
            consumerService.start();
        } catch (Exception e) {
            SecurityLogger.logSecurityEvent("Queue server startup failed: " + e.toString());
            logger.log(Level.SEVERE, "Queue server startup failed", e);
            System.err.println("Queue server startup failed. See server logs for details.");
            System.exit(1);
        }
    }
}
