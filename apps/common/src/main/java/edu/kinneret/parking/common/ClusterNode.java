package edu.kinneret.parking.common;

import java.util.Objects;

/**
 * Represents a single RabbitMQ cluster node that the application can connect to.
 */
public final class ClusterNode {
    private final String host;
    private final int port;
    private final String displayName;
    private final boolean enabled;

    /**
     * Creates a cluster node definition.
     *
     * @param host the node host name or IP address
     * @param port the AMQP port
     * @param displayName the user-friendly node name
     * @param enabled whether this node should be considered for failover
     */
    public ClusterNode(String host, int port, String displayName, boolean enabled) {
        this.host = ValidationUtils.requireNonEmpty(host, "host");
        this.port = ValidationUtils.requirePositive(port, "port");
        this.displayName = ValidationUtils.requireNonEmpty(displayName, "displayName");
        this.enabled = enabled;
    }

    /**
     * Returns the node host name or IP address.
     *
     * @return the configured host
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns the node AMQP port.
     *
     * @return the configured port
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the node display name.
     *
     * @return the configured display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Indicates whether this node is enabled for connection attempts.
     *
     * @return {@code true} when the node may be used
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the host and port pair used in logs and environment parsing.
     *
     * @return the address in {@code host:port} format
     */
    public String toAddress() {
        return host + ":" + port;
    }

    /**
     * Compares this node to another object for value equality.
     *
     * @param other the object to compare with
     * @return {@code true} when both objects describe the same node
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ClusterNode that)) {
            return false;
        }
        return port == that.port
                && enabled == that.enabled
                && host.equals(that.host)
                && displayName.equals(that.displayName);
    }

    /**
     * Returns the hash code for this node definition.
     *
     * @return the hash code used by hashed collections
     */
    @Override
    public int hashCode() {
        return Objects.hash(host, port, displayName, enabled);
    }

    /**
     * Returns a readable string form of the node definition.
     *
     * @return the node details as text
     */
    @Override
    public String toString() {
        return "ClusterNode{"
                + "host='" + host + '\''
                + ", port=" + port
                + ", displayName='" + displayName + '\''
                + ", enabled=" + enabled
                + '}';
    }
}
