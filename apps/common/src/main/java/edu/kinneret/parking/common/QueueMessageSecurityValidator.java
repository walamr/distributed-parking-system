package edu.kinneret.parking.common;

import java.time.Clock;

/**
 * Validates queue messages before they are accepted by the queue server.
 */
public final class QueueMessageSecurityValidator {
    private static final long DEFAULT_MAX_MESSAGE_AGE_SECONDS = 60;

    private final SecureMessageSigner signer;
    private final NonceStore nonceStore;
    private final Clock clock;
    private final long maxMessageAgeSeconds;

    /**
     * Creates a validator with the default 60-second freshness window.
     *
     * @param signer the HMAC signer/verifier
     * @param nonceStore the nonce replay-prevention store
     */
    public QueueMessageSecurityValidator(SecureMessageSigner signer, NonceStore nonceStore) {
        this(signer, nonceStore, Clock.systemUTC(), DEFAULT_MAX_MESSAGE_AGE_SECONDS);
    }

    /**
     * Creates a validator with explicit collaborators.
     *
     * @param signer the HMAC signer/verifier
     * @param nonceStore the nonce replay-prevention store
     * @param clock the clock used for timestamp checks
     * @param maxMessageAgeSeconds the maximum accepted age in seconds
     */
    public QueueMessageSecurityValidator(
            SecureMessageSigner signer,
            NonceStore nonceStore,
            Clock clock,
            long maxMessageAgeSeconds) {
        this.signer = signer;
        this.nonceStore = nonceStore;
        this.clock = clock;
        this.maxMessageAgeSeconds = maxMessageAgeSeconds;
    }

    /**
     * Validates a parsed envelope and returns the decision.
     *
     * @param envelope the parsed message envelope
     * @return the validation result
     */
    public ValidationResult validate(MessageEnvelope envelope) {
        long currentEpochSeconds = clock.instant().getEpochSecond();
        long ageSeconds = currentEpochSeconds - envelope.getTimestampEpochSeconds();
        if (ageSeconds > maxMessageAgeSeconds) {
            return ValidationResult.rejectedResult("timestamp too old");
        }
        if (envelope.getTimestampEpochSeconds() > currentEpochSeconds + maxMessageAgeSeconds) {
            return ValidationResult.rejectedResult("timestamp too far in the future");
        }
        if (!signer.verify(envelope.toSigningContent(), envelope.getHmac())) {
            return ValidationResult.rejectedResult("invalid hmac");
        }
        if (!nonceStore.addNonce(envelope.getNonce())) {
            return ValidationResult.rejectedResult("replayed nonce");
        }
        return ValidationResult.acceptedResult();
    }

    /**
     * Represents the result of queue message validation.
     *
     * @param accepted whether the message was accepted
     * @param reason the rejection reason, or {@code accepted}
     */
    public record ValidationResult(boolean accepted, String reason) {

        /**
         * Creates an accepted result.
         *
         * @return the accepted result
         */
        public static ValidationResult acceptedResult() {
            return new ValidationResult(true, "accepted");
        }

        /**
         * Creates a rejected result.
         *
         * @param reason the rejection reason
         * @return the rejected result
         */
        public static ValidationResult rejectedResult(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
