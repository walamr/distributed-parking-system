package edu.kinneret.parking.customer.ui;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.ParkingRepository;
import org.bson.Document;
import java.util.List;

/**
 * Helper query utility class for testing MongoDB cluster history retrieval.
 */
public class TestQuery {

    /**
     * Default constructor for TestQuery.
     */
    public TestQuery() {
    }

    /**
     * Main entry point to run history query tests.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        try {
            System.out.println("[TEST-QUERY] Loading config...");
            AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.CUSTOMER_UI);
            System.out.println("[TEST-QUERY] Connecting to MongoDB...");
            try (ParkingRepository repo = new ParkingRepository(config)) {
                System.out.println("[TEST-QUERY] Fetching history for 233-47-038...");
                List<Document> history = repo.getVehicleHistory("233-47-038");
                System.out.println("[TEST-QUERY] Succeeded! Returned " + history.size() + " documents.");
                for (Document doc : history) {
                    System.out.println("  - Type: " + doc.getString("type") 
                        + ", Space: " + ParkingRepository.readPayloadField(doc, "spaceId")
                        + ", Timestamp: " + doc.get("timestamp"));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
