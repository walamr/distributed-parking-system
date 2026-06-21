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
import java.util.logging.Logger;

/**
 * Main application for the Storage Service.
 * Consumes messages from RabbitMQ and persists them to MongoDB.
 */
public class StorageServerApplication {
    /**
     * Default constructor for StorageServerApplication.
     */
    public StorageServerApplication() {
        // Default constructor
    }

    private static final Logger logger = Logger.getLogger(StorageServerApplication.class.getName());

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

        MongoStorageService storageService = new MongoStorageService(config, "parking_db");
        RabbitMqConnectionManager connectionManager = new RabbitMqConnectionManager(config);

        try (RabbitMqConnectionManager.ConnectionHandle handle = connectionManager.connect();
             Channel channel = handle.connection().createChannel()) {
            
            // Limit the number of unacknowledged messages
            channel.basicQos(10);

            // Note: Queues and Exchanges are declared by the QueueServer (mulligan_admin).
            // This service only consumes from the pre-existing queues.

            consumeQueue(channel, config.getTransactionsQueueName(), storageService, validator, config);
            consumeQueue(channel, config.getCitationsQueueName(), storageService, validator, config);

            logger.info("Storage Server is now listening to queues via " + handle.activeNode().toAddress());
            
            // Keep the application running
            Thread.currentThread().join();
            
        } catch (Exception e) {
            String host = "unknown";
            try { host = InetAddress.getLocalHost().getHostAddress(); } catch (Exception ignored) {}
            SecurityLogger.logSecurityEvent("CRITICAL ERROR: " + e.toString() + " | Host: " + host);
            logger.severe("Storage Server terminated with a protected error path. See logs/security.log for details.");
        }
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
                
                // Save to MongoDB
                storageService.storeMessage(envelope);
                
                // Audit successful storage (Hardening R1-Audit-Logging)
                SecurityLogger.logAudit("[TRACE: " + envelope.getCorrelationId() + "] Stored " + envelope.getType() + " message | ID: " + envelope.getMessageId() + " | Source: " + envelope.getClientIp());


                // Acknowledge the message
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);

            } catch (Exception e) {
                // LOG ON SERVER SIDE ONLY (Hardening R1-E-01)
                SecurityLogger.logRejection(queueName, "Error: " + SecurityLogger.sanitize(e.getMessage()));
                
                // Reject and don't requeue to avoid infinite loops on bad data
                channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
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
