import edu.kinneret.parking.common.TlsUtils;
public class TestSsl {
    public static void main(String[] args) throws Exception {
        try {
            TlsUtils.createSslContext("docker/rabbitmq/certs/truststore.jks", "password", "docker/rabbitmq/certs/keystore.jks", "password");
            System.out.println("Success!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
