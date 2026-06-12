package edu.kinneret.parking.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link MessageEnvelope}.
 */
class MessageEnvelopeTest {
    /**
     * Should round trip json payload.
     */

    @Test
    void shouldRoundTripJsonPayload() {
        MessageEnvelope envelope = MessageEnvelope.of(
                UUID.fromString("692502a8-0b98-4d8f-b409-23f8d7544097"),
                "9f79f266-44bd-4fe7-ae38-b3ad8e6002b0", // correlationId
                "e5a5a1f2-1234-4567-8901-23f8d7544097", // nonce
                1_778_317_200L, // timestamp
                "127.0.0.1", // clientIp
                "transaction.created", // type
                "{\"note\":\"hello \\\"queue\\\"\"}", // payload
                "abc123"); // hmac

        MessageEnvelope parsedEnvelope = MessageEnvelope.fromJsonString(envelope.toJsonString());

        assertEquals(envelope.toSigningContent(), parsedEnvelope.toSigningContent());
        assertEquals(envelope.getHmac(), parsedEnvelope.getHmac());
    }
    /**
     * Should reject invalid nonce uuid.
     */

    @Test
    void shouldRejectInvalidNonceUuid() {
        assertThrows(IllegalArgumentException.class, () -> MessageEnvelope.of(
                UUID.fromString("692502a8-0b98-4d8f-b409-23f8d7544097"),
                "9f79f266-44bd-4fe7-ae38-b3ad8e6002b0", // correlationId
                "not-a-uuid", // nonce (invalid)
                1_778_317_200L,
                "127.0.0.1",
                "transaction.created",
                "{\"amount\":42}",
                "abc123"));
    }
}
