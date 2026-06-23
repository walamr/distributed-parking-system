package edu.kinneret.parking.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.AMQP;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**

 * Represents a class RabbitMqConnectionManagerTest.

 */

class RabbitMqConnectionManagerTest {
    /**
     * Should configure factory for recovery and tls.
     */

    @Test
    void shouldConfigureFactoryForRecoveryAndTls() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.PEO_UI,
                Map.of(
                        "RABBITMQ_NODES", "localhost:5671,localhost:5673,localhost:5674",
                        "RABBITMQ_TLS_ENABLED", "false",
                        "RABBITMQ_RECOVERY_INTERVAL_MS", "7000",
                        "HMAC_SECRET", "test-secret-1234567890",
                        "RABBITMQ_PASSWORD", "test-pass",
                        "MONGO_PASSWORD", "test-pass"));
        RabbitMqConnectionManager connectionManager = new RabbitMqConnectionManager(config);

        ConnectionFactory factory = connectionManager.buildFactory(config.getRabbitMqNodes().getFirst());

        assertEquals("localhost", factory.getHost());
        assertEquals(5671, factory.getPort());
        assertEquals("/parking", factory.getVirtualHost());
        assertTrue(factory.isAutomaticRecoveryEnabled());
        assertTrue(factory.isTopologyRecoveryEnabled());
        assertEquals(7000, factory.getNetworkRecoveryInterval());
    }

    @Test
    void connectShouldTryNextNodeWhenFirstNodeFails() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of(
                        "RABBITMQ_NODES", "10.0.201.16:5671,10.0.201.17:5671,10.0.201.18:5671",
                        "RABBITMQ_TLS_ENABLED", "false",
                        "HMAC_SECRET", "test-secret-1234567890",
                        "RABBITMQ_PASSWORD", "test-pass",
                        "MONGO_PASSWORD", "test-pass"));
        AtomicInteger attempts = new AtomicInteger();
        RabbitMqConnectionManager manager = new RabbitMqConnectionManager(config, (factory, node, name) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IOException("simulated node down");
            }
            return fakeConnection(null);
        });

        try (RabbitMqConnectionManager.ConnectionHandle handle = manager.connect()) {
            assertEquals("10.0.201.17", handle.activeNode().getHost());
            assertEquals(2, attempts.get());
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void connectShouldReportAllConfiguredNodesWhenEveryNodeFails() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of(
                        "RABBITMQ_NODES", "10.0.201.16:5671,10.0.201.17:5671,10.0.201.18:5671",
                        "RABBITMQ_TLS_ENABLED", "false",
                        "HMAC_SECRET", "test-secret-1234567890",
                        "RABBITMQ_PASSWORD", "test-pass",
                        "MONGO_PASSWORD", "test-pass"));
        RabbitMqConnectionManager manager = new RabbitMqConnectionManager(config, (factory, node, name) -> {
            throw new IOException("simulated all nodes down");
        });

        IllegalStateException ex = assertThrows(IllegalStateException.class, manager::connect);

        assertTrue(ex.getMessage().contains("Unable to connect to any RabbitMQ node"));
        assertTrue(ex.getMessage().contains("10.0.201.16:5671"));
        assertTrue(ex.getMessage().contains("10.0.201.17:5671"));
        assertTrue(ex.getMessage().contains("10.0.201.18:5671"));
    }

    @Test
    void connectErrorIncludesVhostAndClassifiesConnectionFailure() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of(
                        "RABBITMQ_NODES", "10.0.201.16:5671",
                        "RABBITMQ_TLS_ENABLED", "false",
                        "HMAC_SECRET", "test-secret-1234567890",
                        "RABBITMQ_PASSWORD", "test-pass",
                        "MONGO_PASSWORD", "test-pass"));
        RabbitMqConnectionManager manager = new RabbitMqConnectionManager(config, (factory, node, name) -> {
            throw new java.net.ConnectException("Connection refused");
        });

        IllegalStateException ex = assertThrows(IllegalStateException.class, manager::connect);

        assertTrue(ex.getMessage().contains("vhost=/parking"));
        assertTrue(ex.getMessage().contains("lastErrorType=CONNECTION_FAILURE"));
    }

    @Test
    void publisherConfirmsForQueueUsesConfiguredConfirmTimeout() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of(
                        "RABBITMQ_NODES", "10.0.201.16:5671",
                        "RABBITMQ_TLS_ENABLED", "false",
                        "RABBITMQ_PUBLISH_CONFIRM_TIMEOUT_MS", "12345",
                        "HMAC_SECRET", "test-secret-1234567890",
                        "RABBITMQ_PASSWORD", "test-pass",
                        "MONGO_PASSWORD", "test-pass"));
        AtomicLong capturedConfirmTimeout = new AtomicLong(-1);
        Channel channel = fakeChannelCapturingConfirmTimeout(capturedConfirmTimeout);
        RabbitMqConnectionManager manager = new RabbitMqConnectionManager(config,
                (factory, node, name) -> fakeConnection(channel));

        manager.withPublisherConfirmsForQueue(config.getTransactionsQueueName(), (ch, node) ->
                ch.basicPublish("", config.getTransactionsQueueName(),
                        com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN,
                        "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals(12345L, capturedConfirmTimeout.get());
    }

    @Test
    void publisherConfirmsForQueueShouldAllowDurablePublishWhenConsumerCountIsTemporarilyZero() {
        AppConfig config = AppConfig.fromEnvironment(
                AppConfig.ApplicationProfile.CUSTOMER_UI,
                Map.of(
                        "RABBITMQ_NODES", "10.0.201.16:5671",
                        "RABBITMQ_TLS_ENABLED", "false",
                        "HMAC_SECRET", "test-secret-1234567890",
                        "RABBITMQ_PASSWORD", "test-pass",
                        "MONGO_PASSWORD", "test-pass"));
        AtomicInteger publishCalls = new AtomicInteger();
        Channel channel = fakeChannel(publishCalls);
        RabbitMqConnectionManager manager = new RabbitMqConnectionManager(config,
                (factory, node, name) -> fakeConnection(channel));

        manager.withPublisherConfirmsForQueue(config.getTransactionsQueueName(), (ch, node) ->
                ch.basicPublish("", config.getTransactionsQueueName(),
                        com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN,
                        "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals(1, publishCalls.get());
    }

    private static Connection fakeConnection(Channel channel) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if ("createChannel".equals(method.getName())) {
                        return channel;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Channel fakeChannel(AtomicInteger publishCalls) {
        return (Channel) Proxy.newProxyInstance(
                Channel.class.getClassLoader(),
                new Class<?>[] { Channel.class },
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "queueDeclarePassive" -> new AMQP.Queue.DeclareOk() {
                            @Override
                            public String getQueue() {
                                return (String) args[0];
                            }

                            @Override
                            public int getMessageCount() {
                                return 0;
                            }

                            @Override
                            public int getConsumerCount() {
                                return 0;
                            }

                            @Override
                            public int protocolClassId() {
                                return 50;
                            }

                            @Override
                            public int protocolMethodId() {
                                return 11;
                            }

                            @Override
                            public String protocolMethodName() {
                                return "queue.declare-ok";
                            }
                        };
                        case "waitForConfirms" -> true;
                        case "basicPublish" -> {
                            publishCalls.incrementAndGet();
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static Channel fakeChannelCapturingConfirmTimeout(AtomicLong capturedConfirmTimeout) {
        return (Channel) Proxy.newProxyInstance(
                Channel.class.getClassLoader(),
                new Class<?>[] { Channel.class },
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "queueDeclarePassive" -> new AMQP.Queue.DeclareOk() {
                            @Override
                            public String getQueue() {
                                return (String) args[0];
                            }

                            @Override
                            public int getMessageCount() {
                                return 0;
                            }

                            @Override
                            public int getConsumerCount() {
                                return 1;
                            }

                            @Override
                            public int protocolClassId() {
                                return 50;
                            }

                            @Override
                            public int protocolMethodId() {
                                return 11;
                            }

                            @Override
                            public String protocolMethodName() {
                                return "queue.declare-ok";
                            }
                        };
                        case "waitForConfirms" -> {
                            if (args != null && args.length == 1 && args[0] instanceof Long timeout) {
                                capturedConfirmTimeout.set(timeout);
                            }
                            yield true;
                        }
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static Object defaultValue(Class<?> type) throws TimeoutException {
        if (type == Void.TYPE) {
            return null;
        }
        if (type == Boolean.TYPE) {
            return false;
        }
        if (type == Integer.TYPE) {
            return 0;
        }
        if (type == Long.TYPE) {
            return 0L;
        }
        if (type == Float.TYPE) {
            return 0.0f;
        }
        if (type == Double.TYPE) {
            return 0.0d;
        }
        return null;
    }
}
