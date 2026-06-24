package edu.kinneret.parking.queue;

import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.RecoveryListener;
import com.rabbitmq.client.ShutdownSignalException;
import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.MessageEnvelope;
import edu.kinneret.parking.common.NonceStore;
import edu.kinneret.parking.common.ParkingRepository;
import edu.kinneret.parking.common.QueueMessageSecurityValidator;
import edu.kinneret.parking.common.RabbitMqConnectionManager;
import edu.kinneret.parking.common.SecureMessageSigner;
import edu.kinneret.parking.common.SecurityLogger;
import edu.kinneret.parking.common.ValidationUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Consumes queue messages from both required RabbitMQ quorum queues.
 */
public final class QueueConsumerService {
    private static final Logger logger = Logger.getLogger(QueueConsumerService.class.getName());
    static final boolean PERSISTENCE_CONSUMER_ENABLED = false;
    private final AppConfig appConfig;
    private final RabbitMqConnectionManager connectionManager;
    private final QueueMessageSecurityValidator validator;
    private final ParkingRepository repository;

    /**
     * Creates a queue consumer service using the default security validator.
     *
     * @param appConfig the application configuration
     * @param connectionManager the RabbitMQ connection manager
     */
    public QueueConsumerService(AppConfig appConfig, RabbitMqConnectionManager connectionManager) {
        this(
                appConfig,
                connectionManager,
                new QueueMessageSecurityValidator(
                        new SecureMessageSigner(appConfig.getHmacSecret()), 
                        appConfig != null ? new NonceStore(appConfig) : new NonceStore(1200),
                        java.time.Clock.systemUTC(),
                        appConfig != null ? appConfig.getNonceTtlSeconds() : 1200));
    }

    /**
     * Creates a queue consumer service with an explicit validator.
     *
     * @param appConfig the application configuration
     * @param connectionManager the RabbitMQ connection manager
     * @param validator the message validator
     */
    public QueueConsumerService(
            AppConfig appConfig,
            RabbitMqConnectionManager connectionManager,
            QueueMessageSecurityValidator validator) {
        this.appConfig = appConfig;
        this.connectionManager = connectionManager;
        this.validator = validator;
        this.repository = appConfig != null ? new ParkingRepository(appConfig) : null;
    }

    /**
     * Starts consuming both required queues and waits until the process is interrupted.
     */
    public void start() {
        if (!PERSISTENCE_CONSUMER_ENABLED) {
            throw new UnsupportedOperationException(
                    "Queue-server persistence consumers are disabled; storage-server is the sole persistence owner.");
        }
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(shutdownLatch::countDown, "queue-server-shutdown"));
        try (RabbitMqConnectionManager.ConnectionHandle handle = connectionManager.connect();
                Channel channel = handle.connection().createChannel()) {
            attachRecoveryLogging(handle.connection(), handle.activeNode().toAddress());
            channel.basicQos(10);
            registerConsumer(channel, appConfig.getTransactionsQueueName());
            registerConsumer(channel, appConfig.getCitationsQueueName());
            System.out.println("Queue consumer is listening on "
                    + appConfig.getTransactionsQueueName()
                    + " and "
                    + appConfig.getCitationsQueueName()
                    + " via "
                    + handle.activeNode().toAddress());
            awaitShutdown(shutdownLatch);
        } catch (Exception ex) {
            throw new IllegalStateException("Queue consumer failed to start or recover cleanly.", ex);
        }
    }

    /**
     * Parses and validates one raw queue payload, including business-level field checks.
     *
     * @param rawMessage the raw queue payload
     * @return the validation result
     */
    public QueueMessageSecurityValidator.ValidationResult validateMessage(String rawMessage) {
        try {
            ValidationUtils.requireSafePayloadSize(rawMessage, 65536);
            MessageEnvelope envelope = MessageEnvelope.fromJsonString(rawMessage);
            QueueMessageSecurityValidator.ValidationResult securityResult = validator.validate(envelope);
            if (!securityResult.accepted()) {
                return securityResult;
            }

            // Stage 2: Business Payload Validation (Hardening R1-Input-Validation)
            return validateBusinessPayload(envelope);
        } catch (IllegalArgumentException ex) {
            return edu.kinneret.parking.common.QueueMessageSecurityValidator.ValidationResult.rejectedResult("invalid envelope format");
        }
    }

    /**
     * Validates the business payload embedded in an envelope against the configured
     * parking payload rules.
     *
     * @param envelope the message envelope whose payload is to be validated
     * @return an accepted result when the payload passes, or a rejected result with a reason
     */
    private edu.kinneret.parking.common.QueueMessageSecurityValidator.ValidationResult validateBusinessPayload(MessageEnvelope envelope) {
        try {
            double maxAmount = appConfig != null ? appConfig.getMaxAllowedAmount() : 10000.0d;
            ValidationUtils.validateParkingPayload(envelope.getPayload(), maxAmount, envelope.getType());
            return edu.kinneret.parking.common.QueueMessageSecurityValidator.ValidationResult.acceptedResult();
        } catch (Exception ex) {
            SecurityLogger.logRejection("payload-validation", ex.getMessage());
            return edu.kinneret.parking.common.QueueMessageSecurityValidator.ValidationResult.rejectedResult("business validation failed");
        }
    }

    /**
     * Registers a RabbitMQ consumer on the specified queue. Accepted messages are
     * persisted to the repository; rejected messages are nacked and sent to the
     * dead-letter exchange.
     *
     * @param channel   the active RabbitMQ channel
     * @param queueName the name of the queue to consume
     * @throws IOException if the consumer cannot be registered
     */
    private void registerConsumer(Channel channel, String queueName) throws IOException {
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String payload = new String(delivery.getBody(), StandardCharsets.UTF_8);
            QueueMessageSecurityValidator.ValidationResult result = validateMessage(payload);
            long deliveryTag = delivery.getEnvelope().getDeliveryTag();
            if (result.accepted()) {
                try {
                    MessageEnvelope envelope = MessageEnvelope.fromJsonString(payload);
                    if (repository != null) {
                        repository.storeMessage(envelope);
                    }
                    channel.basicAck(deliveryTag, false);
                } catch (Exception ex) {
                    logger.warning("Failed to store validated queue message: " + SecurityLogger.sanitize(ex.getMessage()));
                    channel.basicReject(deliveryTag, false);
                }
            } else {
                channel.basicReject(deliveryTag, false);
                // LOG ON SERVER SIDE ONLY (Hardening R1-E-01)
                SecurityLogger.logRejection(queueName, result.reason());
            }
        };
        CancelCallback cancelCallback = consumerTag ->
                logger.warning("Consumer cancelled for queue " + queueName + " tag=" + consumerTag);
        channel.basicConsume(queueName, false, deliverCallback, cancelCallback);
    }

    /**
     * Blocks the calling thread until the shutdown latch reaches zero.
     *
     * @param shutdownLatch the latch to await
     * @throws IllegalStateException if the thread is interrupted while waiting
     */
    private void awaitShutdown(CountDownLatch shutdownLatch) {
        try {
            shutdownLatch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Queue consumer interrupted while waiting for shutdown.", ex);
        }
    }

    /**
     * Attaches a shutdown listener and, if the connection supports it, a recovery
     * listener that logs automatic reconnection events.
     *
     * @param connection         the active RabbitMQ connection
     * @param initialNodeAddress the address string of the node the connection was opened on
     */
    private void attachRecoveryLogging(com.rabbitmq.client.Connection connection, String initialNodeAddress) {
        connection.addShutdownListener(this::logShutdownSignal);
        if (connection instanceof Recoverable recoverableConnection) {
            recoverableConnection.addRecoveryListener(new RecoveryListener() {
                /**
                 * Callback invoked when the automatic recovery process begins.
                 *
                 * @param recoverable the recoverable object
                 */
                @Override
                public void handleRecoveryStarted(Recoverable recoverable) {
                    logger.info("RabbitMQ automatic recovery started after connection disruption on " + initialNodeAddress);
                }

                /**
                 * Callback invoked when the automatic recovery process successfully completes.
                 *
                 * @param recoverable the recovered object
                 */
                @Override
                public void handleRecovery(Recoverable recoverable) {
                    logger.info("RabbitMQ automatic recovery completed. Consumers were re-registered by the client library.");
                }
            });
        }
    }

    /**
     * Logs a warning when the RabbitMQ connection shuts down unexpectedly.
     *
     * @param shutdownSignal the shutdown signal received from the broker
     */
    private void logShutdownSignal(ShutdownSignalException shutdownSignal) {
        if (shutdownSignal != null && !shutdownSignal.isInitiatedByApplication()) {
            logger.warning("RabbitMQ connection shutdown detected: " + SecurityLogger.sanitize(shutdownSignal.getMessage()));
        }
    }
}
