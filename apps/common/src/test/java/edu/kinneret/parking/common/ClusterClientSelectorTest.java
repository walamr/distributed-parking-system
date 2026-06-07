package edu.kinneret.parking.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ClusterClientSelector}.
 */
class ClusterClientSelectorTest {

    @Test
    void shouldReturnNodesInRoundRobinFailoverOrder() {
        ClusterClientSelector selector = new ClusterClientSelector(List.of(
                new ClusterNode("node1", 5672, "rabbitmq1", true),
                new ClusterNode("node2", 5673, "rabbitmq2", true),
                new ClusterNode("node3", 5674, "rabbitmq3", true)));

        List<ClusterNode> firstOrder = selector.getNodesInFailoverOrder();
        List<ClusterNode> secondOrder = selector.getNodesInFailoverOrder();

        assertEquals(List.of("node1", "node2", "node3"),
                firstOrder.stream().map(ClusterNode::getHost).toList());
        assertEquals(List.of("node2", "node3", "node1"),
                secondOrder.stream().map(ClusterNode::getHost).toList());
    }

    @Test
    void shouldIgnoreDisabledNodes() {
        ClusterClientSelector selector = new ClusterClientSelector(List.of(
                new ClusterNode("node1", 5672, "rabbitmq1", false),
                new ClusterNode("node2", 5673, "rabbitmq2", true)));

        assertEquals(List.of("node2"),
                selector.getNodesInFailoverOrder().stream().map(ClusterNode::getHost).toList());
    }
}
