package edu.kinneret.parking.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.bson.Document;
import org.junit.jupiter.api.Test;

class MongoStorageServiceTest {

    @Test
    void shouldStoreJsonPayloadAsMongoDocument() {
        Object storedPayload = MongoStorageService.toMongoPayloadValue(
                "{\"vehicleId\":\"123-45-678\",\"spaceId\":\"P101\",\"type\":\"start\"}");

        assertInstanceOf(Document.class, storedPayload);
        assertEquals("123-45-678", ((Document) storedPayload).getString("vehicleId"));
    }

    @Test
    void shouldKeepNonJsonPayloadAsRawString() {
        Object storedPayload = MongoStorageService.toMongoPayloadValue("not-json");

        assertEquals("not-json", storedPayload);
    }
}
