package edu.kinneret.parking.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**

 * Represents a class RabbitMqTopologyInitializerTest.

 */

class RabbitMqTopologyInitializerTest {
    /**
     * Should declare quorum queue arguments.
     */

    @Test
    void shouldDeclareQuorumQueueArguments() {
        Map<String, Object> arguments = RabbitMqTopologyInitializer.quorumQueueArguments("transactions.dead");

        assertEquals("quorum", arguments.get("x-queue-type"));
        assertEquals(3, arguments.get("x-quorum-initial-group-size"));
        assertEquals("parking.dlx", arguments.get("x-dead-letter-exchange"));
        assertEquals("transactions.dead", arguments.get("x-dead-letter-routing-key"));
    }

    @Test
    void shouldDeclareCitationQueueWithDeadLetterRoutingKey() {
        Map<String, Object> arguments = RabbitMqTopologyInitializer.quorumQueueArguments("citations.dead");

        assertEquals("quorum", arguments.get("x-queue-type"));
        assertEquals("parking.dlx", arguments.get("x-dead-letter-exchange"));
        assertEquals("citations.dead", arguments.get("x-dead-letter-routing-key"));
    }

    @Test
    void queueServerDelegatesPersistenceToStorageServer() {
        assertEquals("storage-server", QueueServerApplication.PERSISTENCE_OWNER);
        assertEquals(false, QueueConsumerService.PERSISTENCE_CONSUMER_ENABLED);
    }
}
