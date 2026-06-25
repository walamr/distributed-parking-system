package edu.kinneret.parking.queue;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.ClusterNode;
import edu.kinneret.parking.common.RabbitMqConnectionManager;
import edu.kinneret.parking.common.SecurityLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main entry point for the queue-server foundation.
 */
public final class QueueServerApplication {
    private static final Logger logger = Logger.getLogger(QueueServerApplication.class.getName());
    static final String PERSISTENCE_OWNER = "storage-server";

    /** Utility class; instantiation is not permitted. */
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
            logger.info("RabbitMQ topology service configured: "
                    + appConfig.toRedactedSummary()
                    + ", topologyInitializer=true, persistenceConsumerEnabled=false");

            RabbitMqConnectionManager connectionManager = new RabbitMqConnectionManager(appConfig);
            QueueHealthChecker healthChecker = new QueueHealthChecker(connectionManager);
            RabbitMqTopologyInitializer topologyInitializer = new RabbitMqTopologyInitializer(appConfig, connectionManager);
            healthChecker.requireHealthyNode();
            waitForClusterFormation(connectionManager, appConfig);
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

    /**
     * Waits until the expected number of RabbitMQ cluster nodes are reachable before the
     * quorum topology is declared.
     *
     * <p>This is the fix for the one-node-down failover bug: a quorum queue fixes its member
     * set at declaration time to the cluster nodes that are present. If the queues were declared
     * while only one node was up (for example because a single node imported them from
     * {@code definitions.json} at boot), they would have a single member and stopping that node
     * would make the queue unavailable and time out publisher confirms. By waiting for the whole
     * cluster first, {@code x-quorum-initial-group-size=3} places a member on every node, so the
     * queue keeps a quorum (2 of 3) when any single node is stopped.
     *
     * <p>It does not require any specific node (e.g. rabbit1); it accepts any nodes that are
     * reachable. If the cluster has not fully formed within the wait window it proceeds with a
     * loud warning rather than blocking the deployment forever.
     */
    private static void waitForClusterFormation(
            RabbitMqConnectionManager connectionManager,
            AppConfig appConfig) throws InterruptedException {
        int expectedNodes = appConfig.getRabbitMqExpectedNodes();
        List<ClusterNode> configuredNodes = appConfig.getRabbitMqNodes();
        final int maximumAttempts = 90;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            List<String> reachable = new ArrayList<>();
            for (ClusterNode node : configuredNodes) {
                if (connectionManager.isNodeReachable(node)) {
                    reachable.add(node.toAddress());
                }
            }
            System.out.println("RabbitMQ cluster readiness before topology declaration: reachableNodes="
                    + reachable + " (" + reachable.size() + " of " + expectedNodes + " expected).");
            if (reachable.size() >= expectedNodes) {
                System.out.println("RabbitMQ cluster has the expected " + expectedNodes
                        + " node(s) reachable; declaring quorum topology now so members span the cluster.");
                return;
            }
            System.out.println("Waiting for the RabbitMQ cluster to form... attempt "
                    + attempt + " of " + maximumAttempts);
            Thread.sleep(2_000L);
        }
        System.out.println("WARNING: Declaring RabbitMQ topology without all " + expectedNodes
                + " expected nodes reachable. Quorum queues may be created with fewer members and "
                + "may not survive a single-node failure. Start every RabbitMQ node, then recreate "
                + "the queues (see the runbook) if one-node failover does not work.");
        SecurityLogger.logSecurityEvent("Queue-server declared topology before the full RabbitMQ cluster "
                + "was reachable; expected " + expectedNodes + " nodes.");
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
