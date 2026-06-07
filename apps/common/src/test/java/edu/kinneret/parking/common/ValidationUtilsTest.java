package edu.kinneret.parking.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link ValidationUtils}.
 */
class ValidationUtilsTest {

    @Test
    void shouldAcceptValidQueueName() {
        assertEquals("transactions.queue",
                ValidationUtils.requireValidQueueName("transactions.queue", "queue"));
    }

    @Test
    void shouldRejectInvalidQueueName() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.requireValidQueueName("bad queue", "queue"));
    }

    @Test
    void shouldRejectInvalidMessageType() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.requireValidMessageType("bad/type", "type"));
    }

    @Test
    void shouldAcceptPositiveNumbers() {
        assertEquals(3, ValidationUtils.requirePositive(3, "value"));
    }

    @Test
    void shouldAcceptValidUuid() {
        assertEquals(
                "9f79f266-44bd-4fe7-ae38-b3ad8e6002b0",
                ValidationUtils.requireValidUuid("9f79f266-44bd-4fe7-ae38-b3ad8e6002b0", "nonce"));
    }

    @Test
    void shouldRejectInvalidUuid() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.requireValidUuid("not-a-uuid", "nonce"));
    }

    @Test
    void shouldAcceptValidParkingPayload() {
        ValidationUtils.validateParkingPayload("""
                {"vehicleId":"FAKE999","spaceId":"P01","amount":0,"reason":"Expired meter"}
                """, 10000.0d);
    }

    @Test
    void shouldRejectParkingPayloadWithWrongAmountType() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.validateParkingPayload("""
                        {"vehicleId":"FAKE999","spaceId":"P01","amount":"free"}
                        """, 10000.0d));
    }

    @Test
    void shouldRejectParkingPayloadWithUnexpectedCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.validateParkingPayload("""
                        {"reason":"DROP TABLE users; <script>"}
                        """, 10000.0d));
    }

    @Test
    void shouldRejectUnsupportedParkingPayloadField() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.validateParkingPayload("""
                        {"vehicleId":"604-95-839","spaceId":"P01","role":"admin"}
                        """, 10000.0d, "transaction.start"));
    }

    @Test
    void shouldRejectMissingRequiredTransactionFields() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.validateParkingPayload("""
                        {"vehicleId":"604-95-839"}
                        """, 10000.0d, "transaction.start"));
    }

    @Test
    void shouldRejectInvalidSpaceToken() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.validateParkingPayload("""
                        {"vehicleId":"604-95-839","spaceId":"DROP"}
                        """, 10000.0d, "transaction.start"));
    }

    @Test
    void shouldRejectBareDashCost() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.validateParkingPayload("""
                        {"vehicleId":"604-95-839","spaceId":"P01","cost":"-"}
                        """, 10000.0d, "transaction.stop"));
    }
}
