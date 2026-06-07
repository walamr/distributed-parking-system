package edu.kinneret.parking.storage;

import edu.kinneret.parking.common.MessageEnvelope;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small helper used to verify that a serialized message envelope can be parsed
 * successfully by the storage-side message model.
 */
public class TestParse {
    /**
     * Default constructor for TestParse.
     */
    public TestParse() {
        // Default constructor
    }

    private static final Logger logger = Logger.getLogger(TestParse.class.getName());

    /**
     * Runs the message-envelope parsing smoke test.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        try {
            String msg = "{\"messageId\":\"018cd63c-6850-4a95-a185-d6f67b51c979\",\"nonce\":\"fbfcc427-af67-4286-90b4-52648fb166fb\",\"timestamp\":1715374464,\"type\":\"transaction.smoke-test\",\"payload\":\"{\\\"source\\\":\\\"local-smoke-test\\\",\\\"queue\\\":\\\"transactions.queue\\\"}\",\"hmac\":\"pS9uP1v2DXY/vjB/d8n1QZc7p3j4c5tKxLgG0qK8A/4=\"}";
            System.out.println("Message: " + msg);
            MessageEnvelope env = MessageEnvelope.fromJsonString(msg);
            System.out.println("Parsed successfully: " + env.getMessageId());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Message parsing smoke test failed.", e);
            System.err.println("Message parsing smoke test failed. See logs for details.");
        }
    }
}
