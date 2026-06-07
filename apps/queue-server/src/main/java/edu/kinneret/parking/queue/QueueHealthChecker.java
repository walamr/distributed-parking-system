package edu.kinneret.parking.queue;

import edu.kinneret.parking.common.ClusterNode;
import edu.kinneret.parking.common.RabbitMqConnectionManager;

/**
 * Performs a simple health check to confirm that at least one RabbitMQ node is reachable.
 */
public final class QueueHealthChecker {
    private final RabbitMqConnectionManager connectionManager;

    /**
     * Creates a health checker.
     *
     * @param connectionManager the connection manager used for RabbitMQ checks
     */
    public QueueHealthChecker(RabbitMqConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * Confirms that at least one RabbitMQ node can be reached and returns the active node.
     *
     * @return the active RabbitMQ node
     */
    public ClusterNode requireHealthyNode() {
        try (RabbitMqConnectionManager.ConnectionHandle handle = connectionManager.connect()) {
            System.out.println("RabbitMQ health check passed via " + handle.activeNode().toAddress());
            return handle.activeNode();
        } catch (Exception ex) {
            throw new IllegalStateException("RabbitMQ health check failed.", ex);
        }
    }
}
