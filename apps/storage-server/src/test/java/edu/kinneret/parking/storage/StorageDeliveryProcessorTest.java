package edu.kinneret.parking.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.kinneret.parking.common.MessageEnvelope;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StorageDeliveryProcessorTest {
    private static MessageEnvelope startEnvelope() {
        return MessageEnvelope.createUnsigned(
                "transaction.start",
                "{\"vehicleId\":\"123-45-678\",\"spaceId\":\"P01\",\"type\":\"start\"}",
                "127.0.0.1",
                "test-correlation");
    }

    @Test
    void mongoInsertCompletesBeforeAck() {
        List<String> events = new ArrayList<>();
        boolean result = StorageDeliveryProcessor.persistThenAcknowledge(
                "transactions.queue",
                startEnvelope(),
                "mongodb://<redacted>@mongo1/parking_db",
                envelope -> events.add("insert"),
                acknowledger(events));

        assertTrue(result);
        assertEquals(List.of("insert", "ack"), events);
    }

    @Test
    void failedMongoInsertRejectsWithoutAck() {
        List<String> events = new ArrayList<>();
        boolean result = StorageDeliveryProcessor.persistThenAcknowledge(
                "transactions.queue",
                startEnvelope(),
                "mongodb://<redacted>@mongo1/parking_db",
                envelope -> {
                    events.add("insert");
                    throw new IllegalStateException("database unavailable");
                },
                acknowledger(events));

        assertFalse(result);
        assertEquals(List.of("insert", "reject"), events);
        assertFalse(events.contains("ack"));
    }

    @Test
    void storageServerIsThePersistenceOwner() {
        assertEquals("storage-server", StorageServerApplication.PERSISTENCE_OWNER);
    }

    private static StorageDeliveryProcessor.DeliveryAcknowledger acknowledger(List<String> events) {
        return new StorageDeliveryProcessor.DeliveryAcknowledger() {
            @Override
            public void ack() {
                events.add("ack");
            }

            @Override
            public void reject() {
                events.add("reject");
            }
        };
    }
}
