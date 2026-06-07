package edu.kinneret.parking.queue;

import com.rabbitmq.client.MessageProperties;
import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.MessageEnvelope;
import edu.kinneret.parking.common.RabbitMqConnectionManager;
import edu.kinneret.parking.common.SecureMessageSigner;
import java.nio.charset.StandardCharsets;

/**
 * Publishes one local smoke-test message to each required quorum queue.
 */
public final class QueuePublisherSmokeTest {

    private QueuePublisherSmokeTest() {
    }

    /**
     * Runs the local smoke test against the configured RabbitMQ cluster nodes.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        AppConfig appConfig = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.SMOKE_TEST);
        RabbitMqConnectionManager connectionManager = new RabbitMqConnectionManager(appConfig);
        RabbitMqTopologyInitializer topologyInitializer = new RabbitMqTopologyInitializer(appConfig, connectionManager);
        topologyInitializer.initialize();

        publish(connectionManager, appConfig.getTransactionsQueueName(),
                createSignedEnvelope(
                        "transaction.smoke-test",
                        "{\"source\":\"local-smoke-test\",\"queue\":\"transactions.queue\"}"));
        publish(connectionManager, appConfig.getCitationsQueueName(),
                createSignedEnvelope(
                        "citation.smoke-test",
                        "{\"source\":\"local-smoke-test\",\"queue\":\"citations.queue\"}"));
    }

    /**
     * Publishes a single persistent message to the supplied queue.
     *
     * @param connectionManager the connection manager used to access RabbitMQ
     * @param queueName the target queue name
     * @param envelope the envelope to publish
     */
    public static void publish(
            RabbitMqConnectionManager connectionManager,
            String queueName,
            MessageEnvelope envelope) {
        connectionManager.withPublisherConfirms((channel, activeNode) -> {
            channel.basicPublish(
                    "",
                    queueName,
                    MessageProperties.PERSISTENT_TEXT_PLAIN,
                    envelope.toJsonString().getBytes(StandardCharsets.UTF_8));
            System.out.println("Published AND CONFIRMED smoke-test message to " + queueName + " via " + activeNode.toAddress());
        });
    }

    /**
     * Creates a properly signed envelope for smoke-test publishing.
     *
     * @param type the message type
     * @param payload the queue payload
     * @return the signed envelope
     */
    public static MessageEnvelope createSignedEnvelope(String type, String payload) {
        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.SMOKE_TEST);
        SecureMessageSigner signer = new SecureMessageSigner(config.getHmacSecret());
        return MessageEnvelope.createUnsigned(type, payload, "local-smoke-test").sign(signer);
    }
}
