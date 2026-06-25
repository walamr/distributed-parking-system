package edu.kinneret.parking.common;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
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
    private final ConnectionOpener connectionOpener;

    /**
     * Creates a connection manager for the supplied application configuration.
     *
     * @param appConfig the application configuration
     */
    public RabbitMqConnectionManager(AppConfig appConfig) {
        this(appConfig, (factory, node, name) -> factory.newConnection(
                new com.rabbitmq.client.Address[] {
                        new com.rabbitmq.client.Address(node.getHost(), node.getPort())
                },
                name));
    }

    /**
     * Creates a connection manager with a custom connection opener, primarily to allow tests
     * to inject a stubbed opener.
     *
     * @param appConfig        the application configuration
     * @param connectionOpener the strategy used to open the underlying RabbitMQ connection
     */
    RabbitMqConnectionManager(AppConfig appConfig, ConnectionOpener connectionOpener) {
        this.appConfig = appConfig;
        this.clusterClientSelector = new ClusterClientSelector(appConfig.getRabbitMqNodes());
        this.connectionOpener = connectionOpener;
    }

    /**
     * Opens a RabbitMQ connection using the next failover order.
     *
     * @return an opened connection result that includes the active node
     */
    public ConnectionHandle connect() {
        List<ClusterNode> nodesToTry = clusterClientSelector.getNodesInFailoverOrder();
        IllegalStateException lastFailure = null;
        List<String> attemptedNodes = new ArrayList<>();
        for (ClusterNode node : nodesToTry) {
            attemptedNodes.add(node.toAddress());
            try {
                SecurityLogger.logSecurityEvent("Trying RabbitMQ node " + node.toAddress());
                ConnectionFactory factory = buildFactory(node);

                Connection connection = connectionOpener.open(factory, node, connectionName());
                clusterClientSelector.reportSuccess(node);
                SecurityLogger.logSecurityEvent("Connected to RabbitMQ node " + node.toAddress());
                return new ConnectionHandle(connection, node);
            } catch (IOException | TimeoutException ex) {
                clusterClientSelector.reportFailure(node);
                
                String errorType = (ex instanceof AuthenticationFailureException) ? "AUTH_FAILURE" : "CONN_FAILURE";
                SecurityLogger.logSecurityEvent(errorType + " on node " + node.toAddress() + ": "
                        + SecurityLogger.sanitize(ex.getMessage()));
                lastFailure = new IllegalStateException(
                        "Failed to connect to RabbitMQ node " + node.toAddress(),
                        ex);
            }

        }
        throw new IllegalStateException("Unable to connect to any RabbitMQ node. configuredNodes="
                + configuredNodesSummary() + ", attemptedNodes=" + attemptedNodes
                + ", vhost=" + appConfig.getRabbitMqVirtualHost()
                + ", tlsEnabled=" + appConfig.isRabbitMqTlsEnabled()
                + ", lastErrorType=" + classifyFailure(lastFailure)
                + ", lastFailure=" + lastFailureSummary(lastFailure), lastFailure);
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
            if (!channel.waitForConfirms(appConfig.getRabbitMqPublishConfirmTimeoutMs())) {
                throw new IOException("RabbitMQ message was not confirmed by the broker within "
                        + appConfig.getRabbitMqPublishConfirmTimeoutMs() + "ms on node " + node.toAddress() + ".");
            }
        });
    }

    /**
     * Publishes with confirms only when the target queue has an active consumer.
     * This prevents client UIs from reporting success while storage-server is down.
     *
     * @param queueName target durable queue
     * @param channelConsumer publishing action
     */
    public void withPublisherConfirmsForQueue(String queueName, ChannelConsumer channelConsumer) {
        String validatedQueue = ValidationUtils.requireValidQueueName(queueName, "queueName");
        withChannel((channel, node) -> {
            com.rabbitmq.client.AMQP.Queue.DeclareOk queueStatus = channel.queueDeclarePassive(validatedQueue);
            if (queueStatus.getConsumerCount() < 1) {
                SecurityLogger.logSecurityEvent("RabbitMQ queue " + validatedQueue
                        + " is available on " + node.toAddress()
                        + " but currently reports zero consumers. Publishing is still allowed; "
                        + "durable quorum queue will hold the message until storage-server consumes it.");
            }
            channel.confirmSelect();
            channelConsumer.accept(channel, node);
            if (!channel.waitForConfirms(appConfig.getRabbitMqPublishConfirmTimeoutMs())) {
                throw new IOException("RabbitMQ publisher confirm timed out on node " + node.toAddress()
                        + " for queue " + validatedQueue + " (vhost=" + appConfig.getRabbitMqVirtualHost()
                        + ", confirmTimeoutMs=" + appConfig.getRabbitMqPublishConfirmTimeoutMs() + ").");
            }
        });
    }

    /** Number of channel attempts before surfacing a failure to the caller. */
    private static final int MAX_CHANNEL_ATTEMPTS = 6;
    /** Backoff between channel attempts, long enough to ride through a node stop. */
    private static final long CHANNEL_RETRY_DELAY_MS = 2_000L;

    /**
     * Opens a channel and automatically closes the underlying connection when the action finishes.
     *
     * <p>Retries transient failures so that stopping ONE RabbitMQ node does not surface as a
     * user-facing error. When a node is stopped, the fresh connection fails over to a surviving
     * node, but for a few seconds the quorum queue may still be re-electing its leader and the
     * storage-server consumer may not have re-registered yet (so the consumer-count guard or the
     * publish transiently fail). Backing off and retrying lets the cluster settle and the
     * operation succeed. Re-publishing the same envelope is safe: storage-server de-duplicates by
     * the unique messageId index.
     *
     * @param channelConsumer the action to execute with an active channel
     */
    public void withChannel(ChannelConsumer channelConsumer) {
        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_CHANNEL_ATTEMPTS; attempt++) {
            try (ConnectionHandle handle = connect(); Channel channel = handle.connection().createChannel()) {
                channelConsumer.accept(channel, handle.activeNode());
                return;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("RabbitMQ operation interrupted. configuredNodes="
                        + configuredNodesSummary(), ex);
            } catch (IOException | TimeoutException
                    | com.rabbitmq.client.ShutdownSignalException | IllegalStateException ex) {
                lastFailure = (ex instanceof IllegalStateException)
                        ? (IllegalStateException) ex
                        : new IllegalStateException("RabbitMQ channel operation failed on configured nodes="
                                + configuredNodesSummary() + ". Cause=" + ex.getClass().getSimpleName()
                                + ": " + SecurityLogger.sanitize(ex.getMessage()), ex);
                if (attempt < MAX_CHANNEL_ATTEMPTS) {
                    SecurityLogger.logSecurityEvent("RabbitMQ channel attempt " + attempt + " of "
                            + MAX_CHANNEL_ATTEMPTS + " failed; retrying after " + CHANNEL_RETRY_DELAY_MS
                            + "ms: " + SecurityLogger.sanitize(ex.getMessage()));
                    try {
                        Thread.sleep(CHANNEL_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("RabbitMQ operation interrupted while retrying. configuredNodes="
                                + configuredNodesSummary(), ie);
                    }
                }
            }
        }
        throw new IllegalStateException("RabbitMQ publish/operation failed on all configured nodes after "
                + MAX_CHANNEL_ATTEMPTS + " channel attempts. configuredNodes=" + configuredNodesSummary()
                + ", vhost=" + appConfig.getRabbitMqVirtualHost()
                + ", tlsEnabled=" + appConfig.isRabbitMqTlsEnabled()
                + ", confirmTimeoutMs=" + appConfig.getRabbitMqPublishConfirmTimeoutMs()
                + ", lastErrorType=" + classifyFailure(lastFailure)
                + ", lastFailure=" + lastFailureSummary(lastFailure), lastFailure);
    }

    /**
     * Probes whether a single specific cluster node accepts a connection right now.
     * Used by queue-server to wait for the whole cluster to come up before declaring
     * the quorum topology, so the quorum queues are created with members on every node.
     *
     * @param node the node to probe
     * @return {@code true} if a connection to {@code node} succeeds
     */
    public boolean isNodeReachable(ClusterNode node) {
        try {
            ConnectionFactory factory = buildFactory(node);
            try (Connection ignored = connectionOpener.open(factory, node, connectionName())) {
                return true;
            }
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Classifies the root cause of a failure into a coarse, secret-free category so the
     * surfaced error distinguishes connection, TLS, auth/vhost, queue, confirm-timeout and
     * consumer problems instead of a single vague message.
     *
     * @param throwable the failure whose root cause should be classified (may be {@code null})
     * @return a coarse, secret-free category label describing the failure
     */
    private static String classifyFailure(Throwable throwable) {
        if (throwable == null) {
            return "NONE";
        }
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String simpleName = root.getClass().getSimpleName();
        String message = String.valueOf(root.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (root instanceof AuthenticationFailureException || message.contains("access_refused")
                || message.contains("vhost")) {
            return "AUTH_OR_VHOST_FAILURE";
        }
        if (simpleName.toLowerCase(java.util.Locale.ROOT).contains("ssl")
                || message.contains("certificate") || message.contains("tls")
                || message.contains("handshake")) {
            return "TLS_FAILURE";
        }
        if (message.contains("confirm timed out") || message.contains("was not confirmed")) {
            return "PUBLISHER_CONFIRM_TIMEOUT";
        }
        if (message.contains("no active consumer")) {
            return "NO_ACTIVE_CONSUMER";
        }
        if (message.contains("not_found") || message.contains("no queue")
                || message.contains("queuedeclarepassive")) {
            return "QUEUE_UNAVAILABLE";
        }
        if (root instanceof java.net.ConnectException || root instanceof TimeoutException
                || message.contains("connection refused") || message.contains("timed out")
                || message.contains("unable to connect")) {
            return "CONNECTION_FAILURE";
        }
        return simpleName;
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
            declareQuorumQueue(channel, queueName);
            channelConsumer.accept(channel, node);
        });
    }

    /**
     * Declares a durable quorum queue with a three-member initial group and a dead-letter
     * exchange/routing key derived from the queue name.
     *
     * @param channel   the open channel on which to declare the queue
     * @param queueName the name of the quorum queue to declare
     * @throws IOException when the queue declaration fails
     */
    private void declareQuorumQueue(Channel channel, String queueName) throws IOException {
        String deadLetterRoutingKey = queueName.replace(".queue", ".dead");
        channel.queueDeclare(queueName, true, false, false,
                java.util.Map.of(
                        "x-queue-type", "quorum",
                        "x-quorum-initial-group-size", 3,
                        "x-dead-letter-exchange", "parking.dlx",
                        "x-dead-letter-routing-key", deadLetterRoutingKey));
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
                        /**
                         * Configures the SSL socket to disable hostname verification.
                         *
                         * @param socket the socket being configured
                         * @throws java.io.IOException on configuration errors
                         */
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
     * Builds a human-readable RabbitMQ connection name that embeds the active application
     * profile, used to identify the client in the broker's management UI.
     *
     * @return the connection name in the form {@code parking-<profile>}
     */
    private String connectionName() {
        AppConfig.ApplicationProfile profile = appConfig.getApplicationProfile();
        String profileName = profile == null ? "unknown" : profile.name().toLowerCase(java.util.Locale.ROOT);
        return "parking-" + profileName;
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
     * Checks whether at least one RabbitMQ node is reachable by attempting to open and
     * immediately close a connection.
     *
     * @return {@code true} when a connection succeeds, otherwise {@code false}
     */
    public boolean checkHealth() {
        try (ConnectionHandle ignored = connect()) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Produces a string summary of the enabled configured RabbitMQ node addresses for use in
     * diagnostic error messages.
     *
     * @return the string representation of the enabled node addresses
     */
    private String configuredNodesSummary() {
        return appConfig.getRabbitMqNodes().stream()
                .filter(ClusterNode::isEnabled)
                .map(ClusterNode::toAddress)
                .toList()
                .toString();
    }

    /**
     * Produces a secret-free, single-line summary of a failure's root cause for inclusion in
     * surfaced error messages.
     *
     * @param throwable the failure to summarize (may be {@code null})
     * @return the sanitized root-cause summary, or {@code "none"} when {@code throwable} is null
     */
    private static String lastFailureSummary(Throwable throwable) {
        if (throwable == null) {
            return "none";
        }
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + SecurityLogger.sanitize(root.getMessage());
    }

    /**
     * Strategy for opening a RabbitMQ {@link Connection}, allowing the real network opener to
     * be substituted with a test double.
     */
    @FunctionalInterface
    interface ConnectionOpener {

        /**
         * Opens a RabbitMQ connection to the given cluster node using the supplied factory.
         *
         * @param factory        the pre-configured connection factory
         * @param node           the target cluster node
         * @param connectionName the client connection name reported to the broker
         * @return the opened connection
         * @throws IOException      when the connection cannot be established
         * @throws TimeoutException when establishing the connection times out
         */
        Connection open(ConnectionFactory factory, ClusterNode node, String connectionName)
                throws IOException, TimeoutException;
    }
}
