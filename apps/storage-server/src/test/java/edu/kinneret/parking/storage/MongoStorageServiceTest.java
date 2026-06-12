package edu.kinneret.parking.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.bson.Document;
import org.junit.jupiter.api.Test;

/**

 * Represents a class MongoStorageServiceTest.

 */

class MongoStorageServiceTest {
    /**
     * Should store json payload as mongo document.
     */

    @Test
    void shouldStoreJsonPayloadAsMongoDocument() {
        Object storedPayload = MongoStorageService.toMongoPayloadValue(
                "{\"vehicleId\":\"123-45-678\",\"spaceId\":\"P101\",\"type\":\"start\"}");

        assertInstanceOf(Document.class, storedPayload);
        assertEquals("123-45-678", ((Document) storedPayload).getString("vehicleId"));
    }
    /**
     * Should keep non json payload as raw string.
     */

    @Test
    void shouldKeepNonJsonPayloadAsRawString() {
        Object storedPayload = MongoStorageService.toMongoPayloadValue("not-json");

        assertEquals("not-json", storedPayload);
    }
}
