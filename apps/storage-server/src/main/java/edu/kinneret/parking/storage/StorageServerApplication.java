package edu.kinneret.parking.storage;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.MessageEnvelope;
import edu.kinneret.parking.common.SecurityLogger;
import edu.kinneret.parking.common.QueueMessageSecurityValidator;
import edu.kinneret.parking.common.RabbitMqConnectionManager;
import edu.kinneret.parking.common.SecureMessageSigner;
import edu.kinneret.parking.common.NonceStore;
import edu.kinneret.parking.common.ValidationUtils;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Main application for the Storage Service.
 * Consumes messages from RabbitMQ and persists them to MongoDB.
 */
public class StorageServerApplication {
    static final String PERSISTENCE_OWNER = "storage-server";
    static final boolean MANUAL_ACK_ENABLED = true;
    private static final int TOPOLOGY_WAIT_ATTEMPTS = 60;
    private static final long TOPOLOGY_WAIT_DELAY_MS = 2_000L;
    /**
     * Default constructor for StorageServerApplication.
     */
    public StorageServerApplication() {
        // Default constructor
    }

    private static final Logger logger = Logger.getLogger(StorageServerApplication.class.getName());
    private static final long CONSUMER_RECONNECT_DELAY_MS = 5_000L;

    /**
     * Entry point for the Storage Server.
     * Initializes configuration, security validator, and starts queue consumers.
     * 
     * @param args command line arguments
     */
    public static void main(String[] args) {
        logger.info("Starting Storage Server...");
        
        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.STORAGE_SERVER);
        
        // Initialize Security Logic (Hardening Stage 2)
        SecurityLogger.initialize(System.getenv().getOrDefault("SECURITY_LOG_PATH", "logs/storage-security.log"));
        QueueMessageSecurityValidator validator = new QueueMessageSecurityValidator(
            new SecureMessageSigner(config.getHmacSecret()),
            new NonceStore(config),
            java.time.Clock.systemUTC(),
            config.getNonceTtlSeconds()
        );

        logger.info("Persistence owner=" + PERSISTENCE_OWNER + ", MongoDB target="
                + SecurityLogger.sanitize(config.getMongoUri()) + ", database="
                + MongoStorageService.DATABASE_NAME + ", TLS=" + config.isMongoTlsEnabled());
        logger.info("RabbitMQ storage consumer configured: "
                + config.toRedactedSummary()
                + ", publisherConfirmsEnabled=false, consumerManualAckEnabled=" + MANUAL_ACK_ENABLED);
        MongoStorageService storageService = new MongoStorageService(config, MongoStorageService.DATABASE_NAME);
        RabbitMqConnectionManager connectionManager = new RabbitMqConnectionManager(config);

        runConsumersForever(config, storageService, validator, connectionManager);
    }

    private static void runConsumersForever(
            AppConfig config,
            MongoStorageService storageService,
            QueueMessageSecurityValidator validator,
            RabbitMqConnectionManager connectionManager) {
        while (!Thread.currentThread().isInterrupted()) {
            try (RabbitMqConnectionManager.ConnectionHandle handle = connectionManager.connect()) {
                waitForRequiredQueues(handle.connection(), config);

                CountDownLatch connectionClosed = new CountDownLatch(1);
                handle.connection().addShutdownListener(cause -> {
                    logger.warning("RabbitMQ storage connection closed on " + handle.activeNode().toAddress()
                            + "; reconnecting. Cause=" + SecurityLogger.sanitize(String.valueOf(cause)));
                    connectionClosed.countDown();
                });

                try (Channel channel = handle.connection().createChannel()) {
                    channel.basicQos(10);

                    // Queues and exchanges are declared by queue-server. Storage passively verifies
                    // and consumes with manual ACK. It ACKs only after MongoDB persistence succeeds.
                    consumeQueue(channel, config.getTransactionsQueueName(), storageService, validator, config);
                    consumeQueue(channel, config.getCitationsQueueName(), storageService, validator, config);

                    logger.info("Storage Server is now listening to queues via " + handle.activeNode().toAddress()
                            + "; manualAck=" + MANUAL_ACK_ENABLED);
                    connectionClosed.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warning("Storage Server consumer loop interrupted; shutting down.");
            } catch (Exception e) {
                String host = "unknown";
                try { host = InetAddress.getLocalHost().getHostAddress(); } catch (Exception ignored) {}
                SecurityLogger.logSecurityEvent("RabbitMQ storage consumer reconnect needed: "
                        + SecurityLogger.sanitize(e.toString()) + " | Host: " + host);
                logger.warning("Storage Server RabbitMQ consumer disconnected or failed to start; retrying in "
                        + CONSUMER_RECONNECT_DELAY_MS + "ms. Reason="
                        + SecurityLogger.sanitize(e.getMessage()));
                try {
                    Thread.sleep(CONSUMER_RECONNECT_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static void waitForRequiredQueues(com.rabbitmq.client.Connection connection, AppConfig config)
            throws InterruptedException {
        String transactionsQueue = config.getTransactionsQueueName();
        String citationsQueue = config.getCitationsQueueName();
        for (int attempt = 1; attempt <= TOPOLOGY_WAIT_ATTEMPTS; attempt++) {
            try (Channel verificationChannel = connection.createChannel()) {
                passivelyVerifyQueue(verificationChannel, transactionsQueue);
                passivelyVerifyQueue(verificationChannel, citationsQueue);
                logger.info("Required RabbitMQ queues verified: " + transactionsQueue + ", " + citationsQueue);
                return;
            } catch (Exception ex) {
                if (attempt == TOPOLOGY_WAIT_ATTEMPTS) {
                    throw new IllegalStateException(
                            "Required RabbitMQ topology is missing or inaccessible. "
                                    + "Start queue-server so it can declare "
                                    + transactionsQueue + " and " + citationsQueue + ".",
                            ex);
                }
                logger.warning("Waiting for RabbitMQ topology before consuming; attempt "
                        + attempt + " of " + TOPOLOGY_WAIT_ATTEMPTS + ". Reason: "
                        + SecurityLogger.sanitize(ex.getMessage()));
                Thread.sleep(TOPOLOGY_WAIT_DELAY_MS);
            }
        }
    }

    private static void passivelyVerifyQueue(Channel channel, String queueName) throws IOException {
        com.rabbitmq.client.AMQP.Queue.DeclareOk status = channel.queueDeclarePassive(queueName);
        logger.info("Verified RabbitMQ queue=" + queueName
                + ", messagesReady=" + status.getMessageCount()
                + ", consumers=" + status.getConsumerCount());
    }

    /**
     * Configures a RabbitMQ consumer for a specific queue.
     * 
     * @param channel the active RabbitMQ channel
     * @param queueName the name of the queue to consume
     * @param storageService the database storage service
     * @param validator the security validator
     * @throws IOException when queue operations fail
     */
    private static void consumeQueue(Channel channel, String queueName, MongoStorageService storageService, QueueMessageSecurityValidator validator, AppConfig config) throws IOException {
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8).trim().replace("\0", "");
            try {
                MessageEnvelope envelope = MessageEnvelope.fromJsonString(message);
                
                // --- NEW: Cryptographic Verification (Hardening R1-Message-Authentication) ---
                QueueMessageSecurityValidator.ValidationResult result = validator.validate(envelope);
                if (!result.accepted()) {
                    SecurityLogger.logRejection(queueName, result.reason() + " | Source: " + envelope.getClientIp());
                    channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
                    return;
                }

                // --- NEW: Domain-specific Input Validation (Hardening R1-Input-Validation) ---
                validatePayload(envelope.getPayload(), envelope.getType(), config);
                
                logger.info("[TRACE: " + envelope.getCorrelationId() + "] Accepted message from " + queueName + " ID: " + envelope.getMessageId());
                
                StorageDeliveryProcessor.persistThenAcknowledge(
                        queueName,
                        envelope,
                        SecurityLogger.sanitize(config.getMongoUri()),
                        storageService::storeMessage,
                        new StorageDeliveryProcessor.DeliveryAcknowledger() {
                            @Override
                            public void ack() throws Exception {
                                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                                SecurityLogger.logAudit("[TRACE: " + envelope.getCorrelationId()
                                        + "] Stored and ACKed " + envelope.getType() + " message | ID: "
                                        + envelope.getMessageId() + " | Source: " + envelope.getClientIp());
                            }

                            @Override
                            public void reject() throws Exception {
                                channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
                            }

                            @Override
                            public void requeue() throws Exception {
                                // requeue=true: keep the message so it is redelivered once the
                                // MongoDB replica set finishes electing a new primary.
                                channel.basicReject(delivery.getEnvelope().getDeliveryTag(), true);
                            }
                        });

            } catch (Exception e) {
                // LOG ON SERVER SIDE ONLY (Hardening R1-E-01)
                SecurityLogger.logRejection(queueName, "Error: " + SecurityLogger.sanitize(e.getMessage()));
                
                // Reject and don't requeue to avoid infinite loops on bad data
                channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
                logger.warning("Rejected invalid delivery from queue=" + queueName
                        + "; decision=REJECT, deadLettered=true, exceptionClass="
                        + e.getClass().getName() + ", exceptionMessage="
                        + SecurityLogger.sanitize(e.getMessage()));
            }
        };

        channel.basicConsume(queueName, false, deliverCallback, consumerTag -> {});
        logger.info("Started consuming from queue: " + queueName);
    }

    /**
     * Validates the internal fields of the parking payload using GSON.
     * Performs type checking, range checking, and format validation.
     * 
     * @param payload the JSON payload string
     */
    private static void validatePayload(String payload, String messageType, AppConfig config) {
        try {
            ValidationUtils.validateParkingPayload(payload, config.getMaxAllowedAmount(), messageType);
        } catch (Exception e) {
            throw new IllegalArgumentException("Payload validation failed: " + e.getMessage(), e);
        }
    }
}
