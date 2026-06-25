package edu.kinneret.parking.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides load balancing and failover strategies over a list of cluster nodes.
 * Features: Randomized Load Balancing and Circuit Breaker with Exponential Backoff.
 */
public final class ClusterClientSelector {
    private final List<ClusterNode> enabledNodes;
    private final Map<String, Long> blacklist; // Node Address -> Expiry Timestamp
    private final Map<String, Integer> failureCounts; // Node Address -> Sequential Failures

    /**
     * Creates a selector over the provided nodes.
     *
     * @param nodes the configured cluster nodes
     */
    public ClusterClientSelector(List<ClusterNode> nodes) {
        List<ClusterNode> filteredNodes = new ArrayList<>();
        if (nodes != null) {
            for (ClusterNode node : nodes) {
                if (node != null && node.isEnabled()) {
                    filteredNodes.add(node);
                }
            }
        }
        this.enabledNodes = List.copyOf(filteredNodes);
        this.blacklist = new ConcurrentHashMap<>();
        this.failureCounts = new ConcurrentHashMap<>();
    }

    /**
     * Reports a connection failure to a node, triggering the circuit breaker by
     * blacklisting the node for an exponentially increasing duration
     * (30s, 60s, 120s ... capped at roughly 16 minutes).
     *
     * @param node the failing node whose failure count and blacklist expiry are updated
     */
    public void reportFailure(ClusterNode node) {
        String address = node.toAddress();
        int count = failureCounts.getOrDefault(address, 0) + 1;
        failureCounts.put(address, count);

        // Exponential backoff: base 30s * 2^(count-1)
        long backoffMs = 30_000L * (long) Math.pow(2, Math.min(count - 1, 5)); // Cap at 2^5 * 30s = 960s (~16m)
        long expiry = System.currentTimeMillis() + backoffMs;
        
        blacklist.put(address, expiry);
    }

    /**
     * Reports a successful connection, clearing the node from the blacklist and
     * resetting its sequential failure counter.
     *
     * @param node the node that was successfully reached
     */
    public void reportSuccess(ClusterNode node) {
        String address = node.toAddress();
        blacklist.remove(address);
        failureCounts.remove(address);
    }

    private int currentIndex = 0;
    
    /**
     * Returns enabled nodes in a round-robin failover order.
     * Nodes that are blacklisted are skipped unless all nodes are down.
     *
     * @return the ordered list of nodes starting from the current round-robin offset
     */
    public synchronized List<ClusterNode> getNodesInFailoverOrder() {
        if (enabledNodes.isEmpty()) {
            return Collections.emptyList();
        }

        long now = System.currentTimeMillis();
        List<ClusterNode> healthyNodes = new ArrayList<>();
        for (ClusterNode node : enabledNodes) {
            Long expiry = blacklist.get(node.toAddress());
            if (expiry == null || now > expiry) {
                healthyNodes.add(node);
            }
        }

        // If all nodes are blacklisted, try them all anyway as a last resort (Fail-Open)
        List<ClusterNode> candidates = healthyNodes.isEmpty() ? enabledNodes : healthyNodes;

        // Implement Round-Robin rotation
        List<ClusterNode> rotated = new ArrayList<>();
        int size = candidates.size();
        for (int i = 0; i < size; i++) {
            rotated.add(candidates.get((currentIndex + i) % size));
        }
        
        // Increment index for next call
        currentIndex = (currentIndex + 1) % size;
        
        return rotated;
    }

    /**
     * Returns the enabled nodes as configured.
     *
     * @return the immutable configured node list
     */
    public List<ClusterNode> getEnabledNodes() {
        return enabledNodes;
    }
}

