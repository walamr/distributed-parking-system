package edu.kinneret.parking.storage;

import edu.kinneret.parking.common.MessageEnvelope;
import edu.kinneret.parking.common.SecurityLogger;
import java.util.logging.Logger;

/** Persists one validated delivery before making its RabbitMQ ack decision. */
final class StorageDeliveryProcessor {
    private static final Logger logger = Logger.getLogger(StorageDeliveryProcessor.class.getName());
    private static final String SERVICE_NAME = "storage-server";

    /** Utility class; instantiation is not permitted. */
    private StorageDeliveryProcessor() {
    }

    /**
     * Persists a single validated message and then makes the appropriate RabbitMQ acknowledgement
     * decision. On success the delivery is acked. On a transient MongoDB failure (for example a
     * replica-set failover) the delivery is requeued so it can be redelivered; on a permanent
     * failure it is rejected and dead-lettered.
     *
     * @param queueName the queue the delivery came from (for logging)
     * @param envelope the validated message envelope to persist
     * @param sanitizedMongoTarget the sanitized MongoDB target description (for logging)
     * @param store the persistence callback that stores the envelope
     * @param acknowledger the callback used to ack, reject, or requeue the delivery
     * @return {@code true} when the message was stored and acked, {@code false} otherwise
     */
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
            // Distinguish a TRANSIENT MongoDB failure (a node down, a primary election
            // in progress, a socket/timeout) from a PERMANENT one. During a replica-set
            // failover the write must NOT be dropped to the dead-letter queue: requeue it
            // so it is redelivered and persisted once the new primary is available. Only
            // genuinely permanent failures are dead-lettered. (Bad data is already rejected
            // before persistence, and duplicate keys are swallowed inside the store.)
            boolean transientFailure = isTransientMongoError(ex);
            logger.warning("MongoDB insert failed; decision=" + (transientFailure ? "REQUEUE" : "REJECT")
                    + ", deadLettered=" + (!transientFailure) + ", transient=" + transientFailure
                    + ", exceptionClass=" + ex.getClass().getName() + ", exceptionMessage="
                    + SecurityLogger.sanitize(ex.getMessage()) + ", " + context);
            try {
                if (transientFailure) {
                    acknowledger.requeue();
                } else {
                    acknowledger.reject();
                }
            } catch (Exception nackEx) {
                logger.severe("RabbitMQ nack/reject failed for messageId=" + envelope.getMessageId()
                        + ", exceptionClass=" + nackEx.getClass().getName()
                        + ", exceptionMessage=" + SecurityLogger.sanitize(nackEx.getMessage()));
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

    /**
     * Returns true when the failure looks transient (a replica-set node down, a primary
     * election in progress, a socket/connection/server-selection timeout, or a retryable
     * write). Such messages should be requeued rather than dead-lettered so no parking
     * event is lost during a MongoDB failover.
     *
     * @param ex the failure to inspect, including its cause chain
     * @return {@code true} if the failure appears transient and should be retried
     */
    static boolean isTransientMongoError(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof com.mongodb.MongoSocketException
                    || t instanceof com.mongodb.MongoTimeoutException
                    || t instanceof com.mongodb.MongoServerUnavailableException
                    || t instanceof com.mongodb.MongoNotPrimaryException
                    || t instanceof com.mongodb.MongoNodeIsRecoveringException) {
                return true;
            }
            if (t instanceof com.mongodb.MongoException) {
                com.mongodb.MongoException me = (com.mongodb.MongoException) t;
                if (me.hasErrorLabel("RetryableWriteError")
                        || me.hasErrorLabel("TransientTransactionError")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Callback that persists a single message envelope, decoupling this processor from the
     * concrete storage implementation.
     */
    @FunctionalInterface
    interface MessageStore {
        /**
         * Persists the given message envelope.
         *
         * @param envelope the envelope to store
         * @throws Exception if persistence fails
         */
        void store(MessageEnvelope envelope) throws Exception;
    }

    /**
     * Callback abstraction over the three RabbitMQ delivery outcomes (ack, reject, requeue),
     * decoupling this processor from the concrete channel.
     */
    interface DeliveryAcknowledger {
        /**
         * Acknowledges the delivery as successfully processed and persisted.
         *
         * @throws Exception if acknowledging fails
         */
        void ack() throws Exception;

        /**
         * Dead-letters the delivery (permanent failure: never redelivered to this queue).
         *
         * @throws Exception if rejection fails
         */
        void reject() throws Exception;

        /**
         * Returns the delivery to the queue for redelivery (transient failure). Defaults to
         * {@link #reject()} for implementations that do not distinguish the two outcomes.
         *
         * @throws Exception if requeuing fails
         */
        default void requeue() throws Exception {
            reject();
        }
    }
}
