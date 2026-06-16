import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class GenerateServerJks {
    public static void main(String[] args) throws Exception {
        String certPath = "docker/rabbitmq/certs/server-cert.pem";
        String keyPath = "docker/rabbitmq/certs/server-key.pem";
        String jksPath = "docker/rabbitmq/certs/server-keystore.jks";
        String password = "password";

        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        java.security.cert.Certificate cert;
        try (FileInputStream fis = new FileInputStream(certPath)) {
            cert = cf.generateCertificate(fis);
        }

        String keyPem = Files.readString(Paths.get(keyPath), StandardCharsets.UTF_8)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(keyPem);
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, null);
        ks.setKeyEntry("server", privateKey, password.toCharArray(), new java.security.cert.Certificate[]{cert});

        try (FileOutputStream fos = new FileOutputStream(jksPath)) {
            ks.store(fos, password.toCharArray());
        }
        System.out.println("Successfully generated " + jksPath);
    }
}
