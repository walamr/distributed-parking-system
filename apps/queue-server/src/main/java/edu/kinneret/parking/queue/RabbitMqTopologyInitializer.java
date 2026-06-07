package edu.kinneret.parking.queue;

import com.rabbitmq.client.Channel;
import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.RabbitMqConnectionManager;
import java.io.IOException;
import java.util.Map;

/**
 * Declares the durable quorum queues required by the queue server.
 */
public final class RabbitMqTopologyInitializer {
    private final AppConfig appConfig;
    private final RabbitMqConnectionManager connectionManager;

    /**
     * Creates a topology initializer.
     *
     * @param appConfig the application configuration
     * @param connectionManager the connection manager used to reach RabbitMQ
     */
    public RabbitMqTopologyInitializer(AppConfig appConfig, RabbitMqConnectionManager connectionManager) {
        this.appConfig = appConfig;
        this.connectionManager = connectionManager;
    }

    /**
     * Declares the required durable quorum queues.
     */
    public void initialize() {
        connectionManager.withChannel((channel, activeNode) -> {
            // 1. Declare Dead Letter Exchange
            channel.exchangeDeclare("parking.dlx", "direct", true);

            // 2. Declare Dead Letter Queues
            Map<String, Object> dlqArgs = Map.of("x-queue-type", "quorum", "x-quorum-initial-group-size", 3);
            channel.queueDeclare("transactions.dlq", true, false, false, dlqArgs);
            channel.queueDeclare("citations.dlq", true, false, false, dlqArgs);

            // 3. Bind Dead Letter Queues
            channel.queueBind("transactions.dlq", "parking.dlx", "transactions.dead");
            channel.queueBind("citations.dlq", "parking.dlx", "citations.dead");

            // 4. Declare Main Quorum Queues with DLX link
            declareQuorumQueue(channel, appConfig.getTransactionsQueueName(), "transactions.dead");
            declareQuorumQueue(channel, appConfig.getCitationsQueueName(), "citations.dead");
            
            System.out.println("RabbitMQ topology (including DLX) initialized on " + activeNode.toAddress());
        });
    }

    /**
     * Declares a durable quorum queue linked to the dead letter exchange.
     *
     * @param channel the active RabbitMQ channel
     * @param queueName the queue name
     * @param deadLetterRoutingKey the routing key for rejected messages
     * @throws IOException when queue declaration fails
     */
    public void declareQuorumQueue(Channel channel, String queueName, String deadLetterRoutingKey) throws IOException {
        channel.queueDeclare(
                queueName,
                true,
                false,
                false,
                quorumQueueArguments(deadLetterRoutingKey));
    }

    static Map<String, Object> quorumQueueArguments(String deadLetterRoutingKey) {
        return Map.of(
                "x-queue-type", "quorum",
                "x-quorum-initial-group-size", 3,
                "x-dead-letter-exchange", "parking.dlx",
                "x-dead-letter-routing-key", deadLetterRoutingKey);
    }
}
