import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.KeyManagerFactory;
import java.security.KeyStore;
import java.io.FileInputStream;

/**

 * Represents a class TestClient.

 */

public class TestClient {
    /**
     * Main.
     * @param args the args
     * @throws Exception if an error occurs
     */
    public static void main(String[] args) throws Exception {
        System.out.println("Starting test client...");
        String trustStorePath = "docker/rabbitmq/certs/truststore.jks";
        String trustStorePass = "password";
        String keyStorePath = "docker/rabbitmq/certs/keystore.jks";
        String keyStorePass = "password";

        KeyStore ts = KeyStore.getInstance("JKS");
        ts.load(new FileInputStream(trustStorePath), trustStorePass.toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);

        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(new FileInputStream(keyStorePath), keyStorePass.toCharArray());
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyStorePass.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        String recommenderIp = "10.0.201.22";
        java.io.File envFile = new java.io.File("network-ips.env");
        if (!envFile.exists()) {
            envFile = new java.io.File("../network-ips.env");
        }
        if (envFile.exists()) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("RECOMMENDER1_IP=")) {
                        recommenderIp = line.split("=", 2)[1].trim();
                        break;
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }

        try (SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket()) {
            socket.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
            socket.connect(new java.net.InetSocketAddress(recommenderIp, 8091), 3000);
            System.out.println("Socket connected to " + recommenderIp + ":8091");
            socket.startHandshake();
            System.out.println("Handshake successful");
        } catch (Exception e) {
            System.err.println("Test client run failed: " + e.getMessage());
        }
    }
}
