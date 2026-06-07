package edu.kinneret.parking.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link SecureMessageSigner}.
 */
class SecureMessageSignerTest {

    @Test
    void shouldVerifyMatchingSignature() {
        SecureMessageSigner signer = new SecureMessageSigner("test-secret");
        String signature = signer.sign("payload");

        assertTrue(signer.verify("payload", signature));
    }

    @Test
    void shouldRejectDifferentPayload() {
        SecureMessageSigner signer = new SecureMessageSigner("test-secret");
        String signature = signer.sign("payload");

        assertFalse(signer.verify("other-payload", signature));
    }
}
