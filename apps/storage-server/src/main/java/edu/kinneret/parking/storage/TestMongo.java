package edu.kinneret.parking.storage;

import edu.kinneret.parking.common.MessageEnvelope;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small helper used to verify that the storage server can connect to MongoDB
 * and persist a signed message envelope.
 */
public class TestMongo {
    /**
     * Default constructor for TestMongo.
     */
    public TestMongo() {
        // Default constructor
    }

    private static final Logger logger = Logger.getLogger(TestMongo.class.getName());

    /**
     * Runs the MongoDB connectivity smoke test.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        try {
            System.out.println("Connecting to MongoDB...");
            edu.kinneret.parking.common.AppConfig config = edu.kinneret.parking.common.AppConfig.fromEnvironment(
                    edu.kinneret.parking.common.AppConfig.ApplicationProfile.STORAGE_SERVER);
            MongoStorageService service = new MongoStorageService(config, "parking_db");
            MessageEnvelope env = MessageEnvelope.createUnsigned(
                    "transaction.smoke-test",
                    "{\"test\":1}",
                    "local-test");
            System.out.println("Storing message...");
            service.storeMessage(env);
            System.out.println("SUCCESS!");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "MongoDB smoke test failed.", e);
            System.err.println("MongoDB smoke test failed. See logs for details.");
        }
    }
}
