import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.ParkingRepository;
import org.bson.Document;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

public class DbFixer {
    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.QUEUE_SERVER);
        try (ParkingRepository repo = new ParkingRepository(config)) {
            String[] zones = { "Magnolia Way", "Summit Ln", "Fifth Dr", "Downing Ave", "Elm Ct", "Central Way", "Queen St", "Main St", "Lansdowne Blvd", "Adams Ave" };
            double[] rates = { 1.77, 70.23, 56.33, 24.36, 43.27, 35.37, 87.99, 56.22, 17.29, 55.27 };

            for (int i = 1; i <= 100; i++) {
                int zoneIndex = (i - 1) / 10;
                String spaceId = String.valueOf(i);
                String zoneName = zones[zoneIndex];
                double hourlyRate = rates[zoneIndex];

                repo.getDatabase().getCollection("spaces").updateOne(
                        Filters.eq("spaceId", spaceId),
                        Updates.combine(
                                Updates.set("zoneName", zoneName),
                                Updates.set("hourlyRate", hourlyRate)
                        )
                );
            }
            System.out.println("Updated all 100 spaces in MongoDB!");
        }
    }
}
