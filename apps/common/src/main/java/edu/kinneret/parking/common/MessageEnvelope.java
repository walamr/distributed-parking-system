package edu.kinneret.parking.common;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a signed message wrapper used by the queue server.
 */
public final class MessageEnvelope {
    private static final Pattern JSON_PATTERN = Pattern.compile(
            "\\{"
                    + "\"messageId\":\"(?<messageId>(?:\\\\.|[^\"])*)\","
                    + "\"correlationId\":\"(?<correlationId>(?:\\\\.|[^\"])*)\","
                    + "\"nonce\":\"(?<nonce>(?:\\\\.|[^\"])*)\","
                    + "\"timestamp\":(?<timestamp>\\d+),"
                    + "\"clientIp\":\"(?<clientIp>(?:\\\\.|[^\"])*)\","
                    + "\"type\":\"(?<type>(?:\\\\.|[^\"])*)\","
                    + "\"payload\":\"(?<payload>(?:\\\\.|[^\"])*)\","
                    + "\"hmac\":\"(?<hmac>(?:\\\\.|[^\"])*)\""
                    + "\\}");
    private final UUID messageId;
    private final String correlationId;
    private final String nonce;
    private final long timestampEpochSeconds;
    private final String clientIp;
    private final String type;
    private final String payload;
    private final String hmac;

     /**
     * Creates a message envelope.
     *
     * @param messageId the unique message identifier
     * @param correlationId the distributed tracing identifier
     * @param nonce the anti-replay nonce
     * @param timestampEpochSeconds the Unix epoch timestamp in seconds
     * @param clientIp the originating client IP address
     * @param type the message type
     * @param payload the message payload
     * @param hmac the message HMAC signature
     */
    public MessageEnvelope(
            UUID messageId,
            String correlationId,
            String nonce,
            long timestampEpochSeconds,
            String clientIp,
            String type,
            String payload,
            String hmac) {
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.correlationId = ValidationUtils.requireNonEmpty(correlationId, "correlationId");
        this.nonce = ValidationUtils.requireValidUuid(nonce, "nonce");
        this.timestampEpochSeconds = ValidationUtils.requirePositive(timestampEpochSeconds, "timestampEpochSeconds");
        this.clientIp = ValidationUtils.requireNonEmpty(clientIp, "clientIp");
        this.type = ValidationUtils.requireValidMessageType(type, "type");
        this.payload = ValidationUtils.requireNonEmpty(payload, "payload");
        this.hmac = ValidationUtils.requireNonEmpty(hmac, "hmac");
    }


    /**
     * Creates an envelope from explicit field values.
     *
     * @param messageId the unique message identifier
     * @param correlationId the distributed tracing correlation ID
     * @param nonce the UUID nonce
     * @param timestampEpochSeconds the Unix epoch timestamp in seconds
     * @param clientIp the originating client IP address
     * @param type the message type
     * @param payload the payload text
     * @param hmac the HMAC signature
     * @return a new envelope instance
     */
    public static MessageEnvelope of(
            UUID messageId,
            String correlationId,
            String nonce,
            long timestampEpochSeconds,
            String clientIp,
            String type,
            String payload,
            String hmac) {
        return new MessageEnvelope(messageId, correlationId, nonce, timestampEpochSeconds, clientIp, type, payload, hmac);
    }


    /**
     * Creates an unsigned envelope with a placeholder HMAC ready for canonical signing.
     *
     * @param type the message type
     * @param payload the payload text
     * @param clientIp the originating client IP address
     * @return a new envelope instance
     */
    public static MessageEnvelope createUnsigned(String type, String payload, String clientIp) {
        return createUnsigned(type, payload, clientIp, UUID.randomUUID().toString());
    }

    /**
     * Creates an unsigned envelope with an explicit correlation ID.
     *
     * @param type the message type
     * @param payload the payload text
     * @param clientIp the originating client IP address
     * @param correlationId the distributed tracing correlation ID
     * @return a new unsigned envelope ready for signing
     */
    public static MessageEnvelope createUnsigned(String type, String payload, String clientIp, String correlationId) {
        return new MessageEnvelope(
                UUID.randomUUID(),
                correlationId,
                UUID.randomUUID().toString(),
                Instant.now().getEpochSecond(),
                clientIp,
                type,
                payload,
                "pending-hmac");
    }


    /**
     * Returns a copy of the envelope with a different HMAC value.
     *
     * @param hmac the HMAC signature
     * @return the copied envelope
     */
    public MessageEnvelope withHmac(String hmac) {
        return new MessageEnvelope(messageId, correlationId, nonce, timestampEpochSeconds, clientIp, type, payload, hmac);
    }


    /**
     * Returns a signed copy of this envelope using the provided signer.
     *
     * @param signer the HMAC signer
     * @return the signed envelope
     */
    public MessageEnvelope sign(SecureMessageSigner signer) {
        return withHmac(signer.sign(toSigningContent()));
    }

    /**
     * Returns the message identifier.
     *
     * @return the message ID
     */
    public UUID getMessageId() {
        return messageId;
    }

    /**
     * Returns the nonce value.
     *
     * @return the nonce
     */
    public String getNonce() {
        return nonce;
    }

    /**
     * Returns the timestamp in Unix epoch seconds.
     *
     * @return the timestamp
     */
    public long getTimestampEpochSeconds() {
        return timestampEpochSeconds;
    }

    /**
     * Returns the client IP address from the envelope.
     *
     * @return the client IP
     */
    public String getClientIp() {
        return clientIp;
    }

    /**
     * Returns the message type.
     *
     * @return the type
     */
    public String getType() {
        return type;
    }

    /**
     * Returns the payload text.
     *
     * @return the payload
     */
    public String getPayload() {
        return payload;
    }

    /**
     * Returns the HMAC signature.
     *
     * @return the HMAC value
     */
    public String getHmac() {
        return hmac;
    }

    /**
     * Returns the correlation ID used for distributed tracing.
     *
     * @return the correlation ID
     */
    public String getCorrelationId() {
        return correlationId;
    }

    /**
     * Returns a deterministic string representation for signing.
     *
     * @return the signing payload
     */
    public String toSigningContent() {
        return messageId + "|" + correlationId + "|" + nonce + "|" + timestampEpochSeconds + "|" + clientIp + "|" + type + "|" + payload;
    }


    /**
     * Returns a compact JSON-like representation suitable for local smoke tests.
     *
     * @return the envelope as a string payload
     */
    public String toJsonString() {
        return "{"
                + "\"messageId\":\"" + messageId + "\","
                + "\"correlationId\":\"" + escapeJson(correlationId) + "\","
                + "\"nonce\":\"" + escapeJson(nonce) + "\","
                + "\"timestamp\":" + timestampEpochSeconds + ","
                + "\"clientIp\":\"" + escapeJson(clientIp) + "\","
                + "\"type\":\"" + escapeJson(type) + "\","
                + "\"payload\":\"" + escapeJson(payload) + "\","
                + "\"hmac\":\"" + escapeJson(hmac) + "\""
                + "}";
    }


    /**
     * Parses a message envelope from the queue JSON payload format.
     *
     * @param json the queue payload
     * @return the parsed envelope
     */
    public static MessageEnvelope fromJsonString(String json) {
        String sanitizedJson = ValidationUtils.requireNonEmpty(json, "json");
        Matcher matcher = JSON_PATTERN.matcher(sanitizedJson);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Message payload is not a valid MessageEnvelope JSON object.");
        }

        return new MessageEnvelope(
                UUID.fromString(unescapeJson(matcher.group("messageId"))),
                unescapeJson(matcher.group("correlationId")),
                unescapeJson(matcher.group("nonce")),
                Long.parseLong(matcher.group("timestamp")),
                unescapeJson(matcher.group("clientIp")),
                unescapeJson(matcher.group("type")),
                unescapeJson(matcher.group("payload")),
                unescapeJson(matcher.group("hmac")));
    }


    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeJson(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        boolean escaping = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaping) {
                builder.append(current);
                escaping = false;
            } else if (current == '\\') {
                escaping = true;
            } else {
                builder.append(current);
            }
        }
        if (escaping) {
            builder.append('\\');
        }
        return builder.toString();
    }
}
