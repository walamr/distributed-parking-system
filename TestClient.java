import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.KeyManagerFactory;
import java.security.KeyStore;
import java.io.FileInputStream;

public class TestClient {
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

        try (SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket()) {
            socket.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
            socket.connect(new java.net.InetSocketAddress("localhost", 8091), 3000);
            System.out.println("Socket connected to 8091");
            socket.startHandshake();
            System.out.println("Handshake successful");
        } catch (Exception e) {
            System.err.println("Test client run failed: " + e.getMessage());
        }
    }
}
