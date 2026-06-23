package edu.kinneret.parking.customer.ui;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.MessageEnvelope;
import edu.kinneret.parking.common.RabbitMqConnectionManager;
import edu.kinneret.parking.common.SecureMessageSigner;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class TestPublish {
    public static void main(String[] args) {
        try {
            System.out.println("Starting test publish...");
            AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.CUSTOMER_UI);
            System.out.println("Connecting to RabbitMQ cluster...");
            RabbitMqConnectionManager manager = new RabbitMqConnectionManager(config);
            
            String payload = "{\"vehicleId\":\"233-47-038\",\"spaceId\":\"12\",\"areaName\":\"Fifth Dr\",\"type\":\"start\"}";
            SecureMessageSigner signer = new SecureMessageSigner(config.getHmacSecret());
            MessageEnvelope env = MessageEnvelope.createUnsigned("transaction.start", payload, "10.0.201.24", UUID.randomUUID().toString()).sign(signer);
            
            System.out.println("Publishing message...");
            manager.withPublisherConfirmsForQueue(config.getTransactionsQueueName(), (channel, activeNode) -> {
                channel.basicPublish("", config.getTransactionsQueueName(),
                        com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN,
                        env.toJsonString().getBytes(StandardCharsets.UTF_8));
                System.out.println("Message accepted by RabbitMQ via " + activeNode.toAddress());
            });
            
            System.out.println("SUCCESS!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
