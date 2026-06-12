package edu.kinneret.parking.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bson.Document;
import org.junit.jupiter.api.Test;

/**

 * Represents a class ParkingRepositoryCompatibilityTest.

 */

class ParkingRepositoryCompatibilityTest {
    /**
     * Should read nested payload document fields.
     */

    @Test
    void shouldReadNestedPayloadDocumentFields() {
        Document record = new Document("payload", new Document("vehicleId", "123-45-678")
                .append("spaceId", "P101")
                .append("type", "start")
                .append("reason", "Expired Meter"));

        assertEquals("123-45-678", ParkingRepository.readPayloadField(record, "vehicleId"));
        assertEquals("P101", ParkingRepository.readPayloadField(record, "spaceId"));
        assertEquals("Expired Meter", ParkingRepository.readPayloadField(record, "reason"));
        assertEquals("Parking Ok", ParkingRepository.evaluateLegality(record, "P101"));
    }
    /**
     * Should read legacy string payload fields.
     */

    @Test
    void shouldReadLegacyStringPayloadFields() {
        Document record = new Document("payload",
                "{\"vehicleId\":\"123-45-678\",\"spaceId\":\"P101\",\"type\":\"start\",\"reason\":\"Expired Meter\"}");

        assertEquals("123-45-678", ParkingRepository.readPayloadField(record, "vehicleId"));
        assertEquals("P101", ParkingRepository.readPayloadField(record, "spaceId"));
        assertEquals("Expired Meter", ParkingRepository.readPayloadField(record, "reason"));
        assertEquals("Parking Ok", ParkingRepository.evaluateLegality(record, "P101"));
    }
    /**
     * Should fall back to top level transaction type when payload has no type.
     */

    @Test
    void shouldFallBackToTopLevelTransactionTypeWhenPayloadHasNoType() {
        Document record = new Document("type", "transaction.stop")
                .append("payload", new Document("vehicleId", "123-45-678").append("spaceId", "P101"));

        assertEquals("stop", ParkingRepository.readTransactionAction(record));
        assertEquals("Parking Not Ok", ParkingRepository.evaluateLegality(record, "P101"));
    }
    /**
     * Should return null when payload cannot be parsed.
     */

    @Test
    void shouldReturnNullWhenPayloadCannotBeParsed() {
        Document record = new Document("payload", "not-json");

        assertNull(ParkingRepository.readPayloadDocument(record));
        assertEquals("", ParkingRepository.readPayloadField(record, "vehicleId"));
    }
    /**
     * Should return payload document when stored as document.
     */

    @Test
    void shouldReturnPayloadDocumentWhenStoredAsDocument() {
        Document payload = new Document("vehicleId", "123-45-678");
        Document record = new Document("payload", payload);

        assertNotNull(ParkingRepository.readPayloadDocument(record));
        assertEquals("123-45-678", ParkingRepository.readPayloadDocument(record).getString("vehicleId"));
    }
}
