import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.ParkingRepository;

public class ZoneTester {
    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.fromEnvironment(AppConfig.ApplicationProfile.CUSTOMER_UI);
        try (ParkingRepository repo = new ParkingRepository(config)) {
            System.out.println("Zone for 10: " + repo.getSpaceZone("10"));
            System.out.println("Zone for 20: " + repo.getSpaceZone("20"));
            System.out.println("Zone for 9: " + repo.getSpaceZone("9"));
            System.out.println("Zone for 11: " + repo.getSpaceZone("11"));
        }
    }
}
