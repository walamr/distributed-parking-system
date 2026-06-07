package edu.kinneret.parking.queue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.kinneret.parking.common.MessageEnvelope;
import edu.kinneret.parking.common.NonceStore;
import edu.kinneret.parking.common.QueueMessageSecurityValidator;
import edu.kinneret.parking.common.SecureMessageSigner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link QueueMessageSecurityValidator}.
 */
class QueueMessageSecurityValidatorTest {

    @Test
    void shouldAcceptValidSignedEnvelope() {
        QueueMessageSecurityValidator validator = createValidator(Instant.parse("2026-05-09T10:00:00Z"));
        MessageEnvelope envelope = signedEnvelope(
                "transaction.created",
                "{\"amount\":42}",
                "2de50ea2-c689-490b-8eb7-45ef7dd1dbff",
                Instant.parse("2026-05-09T09:59:45Z").getEpochSecond());

        QueueMessageSecurityValidator.ValidationResult result = validator.validate(envelope);

        assertTrue(result.accepted());
    }

    @Test
    void shouldRejectInvalidHmac() {
        QueueMessageSecurityValidator validator = createValidator(Instant.parse("2026-05-09T10:00:00Z"));
        MessageEnvelope envelope = MessageEnvelope.of(
                UUID.fromString("692502a8-0b98-4d8f-b409-23f8d7544097"),
                "9f79f266-44bd-4fe7-ae38-b3ad8e6002b0", // correlationId
                "2de50ea2-c689-490b-8eb7-45ef7dd1dbff", // nonce
                Instant.parse("2026-05-09T09:59:45Z").getEpochSecond(),
                "127.0.0.1",
                "transaction.created",
                "{\"amount\":42}",
                "bad-signature");

        QueueMessageSecurityValidator.ValidationResult result = validator.validate(envelope);

        assertFalse(result.accepted());
    }

    @Test
    void shouldRejectOldTimestamp() {
        QueueMessageSecurityValidator validator = createValidator(Instant.parse("2026-05-09T10:00:00Z"));
        MessageEnvelope envelope = signedEnvelope(
                "transaction.created",
                "{\"amount\":42}",
                "91627b4d-31ee-4e3f-a517-6f2a65f0f5dc",
                Instant.parse("2026-05-09T09:58:30Z").getEpochSecond());

        QueueMessageSecurityValidator.ValidationResult result = validator.validate(envelope);

        assertFalse(result.accepted());
    }

    @Test
    void shouldRejectReplayedNonce() {
        QueueMessageSecurityValidator validator = createValidator(Instant.parse("2026-05-09T10:00:00Z"));
        MessageEnvelope firstEnvelope = signedEnvelope(
                "citation.created",
                "{\"plate\":\"123-45-678\"}",
                "2a4d5954-d547-4e1e-8246-fd6cd485862c",
                Instant.parse("2026-05-09T09:59:45Z").getEpochSecond());
        MessageEnvelope replayEnvelope = signedEnvelope(
                "citation.created",
                "{\"plate\":\"123-45-678\"}",
                "2a4d5954-d547-4e1e-8246-fd6cd485862c",
                Instant.parse("2026-05-09T09:59:50Z").getEpochSecond());

        assertTrue(validator.validate(firstEnvelope).accepted());
        assertFalse(validator.validate(replayEnvelope).accepted());
    }

    @Test
    void shouldAcceptValidUuidNonceThroughJsonParsing() {
        QueueConsumerService consumerService = new QueueConsumerService(
                null,
                null,
                createValidator(Instant.parse("2026-05-09T10:00:00Z")));
        MessageEnvelope envelope = signedEnvelope(
                "citation.created",
                "{\"vehicleId\":\"123-45-678\",\"spaceId\":\"P01\",\"amount\":100,\"reason\":\"Expired meter\"}",
                "9f79f266-44bd-4fe7-ae38-b3ad8e6002b0",
                Instant.parse("2026-05-09T09:59:45Z").getEpochSecond());

        QueueMessageSecurityValidator.ValidationResult result = consumerService.validateMessage(envelope.toJsonString());

        assertTrue(result.accepted());
    }

    @Test
    void shouldRejectMalformedEnvelopeJson() {
        QueueConsumerService consumerService = new QueueConsumerService(
                null,
                null,
                createValidator(Instant.parse("2026-05-09T10:00:00Z")));

        QueueMessageSecurityValidator.ValidationResult result =
                consumerService.validateMessage("{\"messageId\":\"not-a-real-envelope\"}");

        assertFalse(result.accepted());
    }

    @Test
    void shouldRejectNegativeAmountInPayload() {
        QueueConsumerService consumerService = new QueueConsumerService(
                null,
                null,
                createValidator(Instant.parse("2026-05-09T10:00:00Z")));
        MessageEnvelope envelope = signedEnvelope(
                "transaction.created",
                "{\"vehicleId\":\"FAKE999\",\"spaceId\":\"P01\",\"amount\":-1}",
                "f0b17752-52f4-4a52-baea-097c20f4c364",
                Instant.parse("2026-05-09T09:59:45Z").getEpochSecond());

        QueueMessageSecurityValidator.ValidationResult result = consumerService.validateMessage(envelope.toJsonString());

        assertFalse(result.accepted());
    }

    @Test
    void shouldRejectWrongJsonTypeInPayload() {
        QueueConsumerService consumerService = new QueueConsumerService(
                null,
                null,
                createValidator(Instant.parse("2026-05-09T10:00:00Z")));
        MessageEnvelope envelope = signedEnvelope(
                "citation.created",
                "{\"vehicleId\":\"FAKE999\",\"spaceId\":\"P01\",\"amount\":\"zero\"}",
                "93062560-1eca-45f2-bfbc-e570ad61f7ab",
                Instant.parse("2026-05-09T09:59:45Z").getEpochSecond());

        QueueMessageSecurityValidator.ValidationResult result = consumerService.validateMessage(envelope.toJsonString());

        assertFalse(result.accepted());
    }

    @Test
    void shouldRejectMissingHmacField() {
        QueueConsumerService consumerService = new QueueConsumerService(
                null,
                null,
                createValidator(Instant.parse("2026-05-09T10:00:00Z")));
        String jsonWithoutHmac = signedEnvelope(
                "transaction.created",
                "{\"vehicleId\":\"604-95-839\",\"spaceId\":\"P01\"}",
                "72d4f807-0563-4609-9f34-d01ce6ac4245",
                Instant.parse("2026-05-09T09:59:45Z").getEpochSecond())
                .toJsonString()
                .replaceAll(",\"hmac\":\"[^\"]+\"", "");

        QueueMessageSecurityValidator.ValidationResult result = consumerService.validateMessage(jsonWithoutHmac);

        assertFalse(result.accepted());
    }

    @Test
    void shouldRejectMissingNonceField() {
        QueueConsumerService consumerService = new QueueConsumerService(
                null,
                null,
                createValidator(Instant.parse("2026-05-09T10:00:00Z")));
        String jsonWithoutNonce = signedEnvelope(
                "transaction.created",
                "{\"vehicleId\":\"604-95-839\",\"spaceId\":\"P01\"}",
                "7802cbb8-704a-461d-adc2-7ca25df15668",
                Instant.parse("2026-05-09T09:59:45Z").getEpochSecond())
                .toJsonString()
                .replaceAll(",\"nonce\":\"[^\"]+\"", "");

        QueueMessageSecurityValidator.ValidationResult result = consumerService.validateMessage(jsonWithoutNonce);

        assertFalse(result.accepted());
    }

    @Test
    void shouldRejectInvalidParkingSpaceInPayload() {
        QueueConsumerService consumerService = new QueueConsumerService(
                null,
                null,
                createValidator(Instant.parse("2026-05-09T10:00:00Z")));
        MessageEnvelope envelope = signedEnvelope(
                "transaction.created",
                "{\"vehicleId\":\"604-95-839\",\"spaceId\":\"DROP\"}",
                "ac0f6569-bf92-4c14-9dd0-16971db6a7d2",
                Instant.parse("2026-05-09T09:59:45Z").getEpochSecond());

        QueueMessageSecurityValidator.ValidationResult result = consumerService.validateMessage(envelope.toJsonString());

        assertFalse(result.accepted());
    }

    @Test
    void shouldRejectOversizedPayloadInput() {
        QueueConsumerService consumerService = new QueueConsumerService(
                null,
                null,
                createValidator(Instant.parse("2026-05-09T10:00:00Z")));
        String oversizedReason = "A".repeat(256);
        MessageEnvelope envelope = signedEnvelope(
                "citation.created",
                "{\"vehicleId\":\"604-95-839\",\"spaceId\":\"P01\",\"amount\":100,\"reason\":\"" + oversizedReason + "\"}",
                "78e9944d-84e9-4e52-9c65-2be090fbfdb6",
                Instant.parse("2026-05-09T09:59:45Z").getEpochSecond());

        QueueMessageSecurityValidator.ValidationResult result = consumerService.validateMessage(envelope.toJsonString());

        assertFalse(result.accepted());
    }

    @Test
    void shouldRejectInjectionLikePayloadInput() {
        QueueConsumerService consumerService = new QueueConsumerService(
                null,
                null,
                createValidator(Instant.parse("2026-05-09T10:00:00Z")));
        MessageEnvelope envelope = signedEnvelope(
                "citation.created",
                "{\"vehicleId\":\"604-95-839\",\"spaceId\":\"P01\",\"amount\":100,\"reason\":\"<script>alert(1)</script>\"}",
                "14dd34b9-b976-443e-b3fa-a3a6eeac4d45",
                Instant.parse("2026-05-09T09:59:45Z").getEpochSecond());

        QueueMessageSecurityValidator.ValidationResult result = consumerService.validateMessage(envelope.toJsonString());

        assertFalse(result.accepted());
    }

    private static QueueMessageSecurityValidator createValidator(Instant now) {
        return new QueueMessageSecurityValidator(
                new SecureMessageSigner("test-secret"),
                new NonceStore(60),
                Clock.fixed(now, ZoneOffset.UTC),
                60);
    }

    private static MessageEnvelope signedEnvelope(String type, String payload, String nonce, long timestamp) {
        SecureMessageSigner signer = new SecureMessageSigner("test-secret");
        MessageEnvelope unsignedEnvelope = MessageEnvelope.of(
                UUID.fromString("692502a8-0b98-4d8f-b409-23f8d7544097"),
                "9f79f266-44bd-4fe7-ae38-b3ad8e6002b0", // correlationId
                nonce,
                timestamp,
                "127.0.0.1",
                type,
                payload,
                "pending-hmac");
        return unsignedEnvelope.withHmac(signer.sign(unsignedEnvelope.toSigningContent()));
    }
}
