package edu.kinneret.parking.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RabbitMqTopologyInitializerTest {

    @Test
    void shouldDeclareQuorumQueueArguments() {
        Map<String, Object> arguments = RabbitMqTopologyInitializer.quorumQueueArguments("transactions.dead");

        assertEquals("quorum", arguments.get("x-queue-type"));
        assertEquals(3, arguments.get("x-quorum-initial-group-size"));
        assertEquals("parking.dlx", arguments.get("x-dead-letter-exchange"));
        assertEquals("transactions.dead", arguments.get("x-dead-letter-routing-key"));
    }
}
