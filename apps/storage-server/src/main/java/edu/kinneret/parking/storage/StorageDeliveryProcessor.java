package edu.kinneret.parking.storage;

import edu.kinneret.parking.common.MessageEnvelope;
import edu.kinneret.parking.common.SecurityLogger;
import java.util.logging.Logger;

/** Persists one validated delivery before making its RabbitMQ ack decision. */
final class StorageDeliveryProcessor {
    private static final Logger logger = Logger.getLogger(StorageDeliveryProcessor.class.getName());
    private static final String SERVICE_NAME = "storage-server";

    private StorageDeliveryProcessor() {
    }

    static boolean persistThenAcknowledge(
            String queueName,
            MessageEnvelope envelope,
            String sanitizedMongoTarget,
            MessageStore store,
            DeliveryAcknowledger acknowledger) {
        String collection = MongoStorageService.collectionNameFor(envelope);
        String context = "service=" + SERVICE_NAME
                + ", queue=" + queueName
                + ", messageId=" + envelope.getMessageId()
                + ", correlationId=" + envelope.getCorrelationId()
                + ", type=" + envelope.getType()
                + ", mongoTarget=" + sanitizedMongoTarget
                + ", database=" + MongoStorageService.DATABASE_NAME
                + ", collection=" + collection;
        logger.info("Consumed validated message; persistence starting: " + context);
        try {
            store.store(envelope);
        } catch (Exception ex) {
            logger.warning("MongoDB insert failed; decision=REJECT, deadLettered=true, exceptionClass="
                    + ex.getClass().getName() + ", exceptionMessage="
                    + SecurityLogger.sanitize(ex.getMessage()) + ", " + context);
            try {
                acknowledger.reject();
            } catch (Exception rejectEx) {
                logger.severe("RabbitMQ reject failed for messageId=" + envelope.getMessageId()
                        + ", exceptionClass=" + rejectEx.getClass().getName()
                        + ", exceptionMessage=" + SecurityLogger.sanitize(rejectEx.getMessage()));
            }
            return false;
        }
        logger.info("MongoDB insert succeeded; decision=ACK: " + context);
        try {
            acknowledger.ack();
            logger.info("RabbitMQ ACK succeeded: " + context);
            return true;
        } catch (Exception ackEx) {
            logger.severe("MongoDB insert succeeded but RabbitMQ ACK failed; message may be redelivered, messageId="
                    + envelope.getMessageId() + ", exceptionClass=" + ackEx.getClass().getName()
                    + ", exceptionMessage=" + SecurityLogger.sanitize(ackEx.getMessage()));
            return false;
        }
    }

    @FunctionalInterface
    interface MessageStore {
        void store(MessageEnvelope envelope) throws Exception;
    }

    interface DeliveryAcknowledger {
        void ack() throws Exception;

        void reject() throws Exception;
    }
}
