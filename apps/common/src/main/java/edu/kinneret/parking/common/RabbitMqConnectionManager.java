package edu.kinneret.parking.common;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import com.rabbitmq.client.AuthenticationFailureException;
import javax.net.ssl.SSLContext;

// Trigger IDE refresh
/**
 * Handles RabbitMQ connection attempts with simple failover across cluster nodes.
 */
public final class RabbitMqConnectionManager {
    private final AppConfig appConfig;
    private final ClusterClientSelector clusterClientSelector;

    /**
     * Creates a connection manager for the supplied application configuration.
     *
     * @param appConfig the application configuration
     */
    public RabbitMqConnectionManager(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.clusterClientSelector = new ClusterClientSelector(appConfig.getRabbitMqNodes());
    }

    /**
     * Opens a RabbitMQ connection using the next failover order.
     *
     * @return an opened connection result that includes the active node
     */
    public ConnectionHandle connect() {
        List<ClusterNode> nodesToTry = clusterClientSelector.getNodesInFailoverOrder();
        IllegalStateException lastFailure = null;
        for (ClusterNode node : nodesToTry) {
            try {
                ConnectionFactory factory = buildFactory(node);
                Connection connection = factory.newConnection("queue-server");
                clusterClientSelector.reportSuccess(node); // --- NEW: Reset failure counter ---
                return new ConnectionHandle(connection, node);
            } catch (IOException | TimeoutException ex) {
                // --- NEW: Circuit Breaker Trigger ---
                clusterClientSelector.reportFailure(node);
                
                String errorType = (ex instanceof AuthenticationFailureException) ? "AUTH_FAILURE" : "CONN_FAILURE";
                SecurityLogger.logSecurityEvent(errorType + " on node " + node.toAddress() + ": "
                        + SecurityLogger.sanitize(ex.getMessage()));
                lastFailure = new IllegalStateException(
                        "Failed to connect to RabbitMQ node " + node.toAddress(),
                        ex);
            }

        }
        throw new IllegalStateException("Unable to connect to any RabbitMQ node.", lastFailure);
    }

    /**
     * Opens a channel and executes the action with Publisher Confirms enabled.
     * Ensures the message is acknowledged by the broker (and replicated to quorum) before returning.
     *
     * @param channelConsumer the action to execute
     */
    public void withPublisherConfirms(ChannelConsumer channelConsumer) {
        withChannel((channel, node) -> {
            channel.confirmSelect();
            channelConsumer.accept(channel, node);
            if (!channel.waitForConfirms(5000)) {
                throw new IOException("RabbitMQ message was not confirmed by the broker within timeout.");
            }
        });
    }

    /**
     * Opens a channel and automatically closes the underlying connection when the action finishes.
     *
     * @param channelConsumer the action to execute with an active channel
     */
    public void withChannel(ChannelConsumer channelConsumer) {
        try (ConnectionHandle handle = connect(); Channel channel = handle.connection().createChannel()) {
            channelConsumer.accept(channel, handle.activeNode());
        } catch (IOException | TimeoutException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("RabbitMQ channel operation failed.", ex);
        }
    }

    /**
     * Opens a channel, ensures the target queue exists, and executes the consumer action.
     * Satisfies the 'Declare and Use' requirement for client interfaces.
     *
     * @param queueName the queue name to declare and use
     * @param channelConsumer the action to execute with the open channel
     */
    public void withChannelForQueue(String queueName, ChannelConsumer channelConsumer) {
        withChannel((channel, node) -> {
            // Durable Quorum Queue Declaration with DLX Link (Idempotent)
            String deadLetterRoutingKey = queueName.replace(".queue", ".dead");
            channel.queueDeclare(queueName, true, false, false, 
                java.util.Map.of(
                    "x-queue-type", "quorum", 
                    "x-quorum-initial-group-size", 3,
                    "x-dead-letter-exchange", "parking.dlx",
                    "x-dead-letter-routing-key", deadLetterRoutingKey
                ));
            channelConsumer.accept(channel, node);
        });
    }

    /**
     * Builds a {@link ConnectionFactory} pre-configured with credentials, timeouts,
     * and optional mTLS settings for the given cluster node.
     *
     * @param node the target RabbitMQ cluster node
     * @return the configured connection factory
     * @throws IllegalStateException if the TLS context cannot be initialised
     */
    ConnectionFactory buildFactory(ClusterNode node) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(node.getHost());
        factory.setPort(node.getPort());
        factory.setUsername(appConfig.getRabbitMqUsername());
        factory.setPassword(appConfig.getRabbitMqPassword());
        factory.setVirtualHost(appConfig.getRabbitMqVirtualHost());
        factory.setConnectionTimeout(appConfig.getRabbitMqConnectionTimeoutMs());
        factory.setRequestedHeartbeat((int) Duration.ofSeconds(30).getSeconds());
        factory.setAutomaticRecoveryEnabled(true);
        factory.setTopologyRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(appConfig.getRabbitMqRecoveryIntervalMs());
        if (appConfig.isRabbitMqTlsEnabled()) {
            try {
                SSLContext sslContext = TlsUtils.createSslContext(
                        appConfig.getTlsTruststorePath(),
                        appConfig.getTlsTruststorePassword(),
                        appConfig.getTlsKeystorePath(),
                        appConfig.getTlsKeystorePassword());
                
                // --- CRITICAL: Ensure mTLS sends the client certificate ---
                factory.setSocketFactory(sslContext.getSocketFactory());

                // --- NEW: Guaranteed Hostname Bypass via SocketConfigurator ---
                if (appConfig.isRabbitMqTlsAllowInvalidHostnames()) {
                    factory.setSocketConfigurator(new com.rabbitmq.client.DefaultSocketConfigurator() {
                        @Override
                        public void configure(java.net.Socket socket) throws java.io.IOException {
                            if (socket instanceof javax.net.ssl.SSLSocket) {
                                javax.net.ssl.SSLSocket sslSocket = (javax.net.ssl.SSLSocket) socket;
                                javax.net.ssl.SSLParameters params = sslSocket.getSSLParameters();
                                params.setEndpointIdentificationAlgorithm("");
                                sslSocket.setSSLParameters(params);
                            }
                        }
                    });
                }
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to initialize RabbitMQ TLS context.", ex);
            }
        }
        return factory;
    }

    /**
     * Represents an active RabbitMQ connection and the node that accepted it.
     *
     * @param connection the open RabbitMQ connection
     * @param activeNode the node that accepted the connection
     */
    public record ConnectionHandle(Connection connection, ClusterNode activeNode) implements AutoCloseable {

        /**
         * Closes the underlying RabbitMQ connection.
         *
         * @throws IOException when the broker connection cannot be closed cleanly
         */
        @Override
        public void close() throws IOException {
            connection.close();
        }
    }

    /**
     * Functional interface for channel work that also exposes the active node.
     */
    @FunctionalInterface
    public interface ChannelConsumer {

        /**
         * Performs work using an active RabbitMQ channel.
         *
         * @param channel the open channel
         * @param activeNode the node currently serving the connection
         * @throws IOException when RabbitMQ I/O fails
         * @throws InterruptedException if the thread is interrupted
         * @throws java.util.concurrent.TimeoutException if the operation times out
         */
        void accept(Channel channel, ClusterNode activeNode) throws IOException, InterruptedException, java.util.concurrent.TimeoutException;
    }

    /**
     * Checks if at least one RabbitMQ node is reachable.
     * @return true if healthy
     */
    public boolean checkHealth() {
        try (ConnectionHandle ignored = connect()) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
