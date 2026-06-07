package edu.kinneret.parking.common;

import java.io.FileInputStream;
import java.io.File;
import java.security.KeyStore;

import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Utility for loading TLS keystores and truststores for mTLS communication.
 */
public final class TlsUtils {

    private TlsUtils() {
    }

    /**
     * Resolves a file path by searching locally, one directory up, or two directories up.
     *
     * @param path the path to resolve
     * @return the resolved absolute path or the original path
     */
    public static String resolvePath(String path) {
        if (path == null || path.isEmpty()) return path;
        File file = new File(path);
        if (file.exists()) {
            return path;
        }
        File oneUp = new File("../" + path);
        if (oneUp.exists()) {
            return oneUp.getAbsolutePath();
        }
        File twoUp = new File("../../" + path);
        if (twoUp.exists()) {
            return twoUp.getAbsolutePath();
        }
        return path;
    }

    /**
     * Creates an SSLContext using the provided truststore and keystore.
     *
     * @param truststorePath the path to the JKS truststore
     * @param truststorePassword the truststore password
     * @param keystorePath the path to the JKS keystore
     * @param keystorePassword the keystore password
     * @return the initialized SSLContext
     * @throws Exception if an error occurs while initializing the SSL context
     */
    public static SSLContext createSslContext(String truststorePath, String truststorePassword,
                                            String keystorePath, String keystorePassword) throws Exception {
        
        String resolvedTrust = resolvePath(truststorePath);
        String resolvedKey = resolvePath(keystorePath);

        // --- Strict Security Policy: Fail-Closed if Certificates Missing ---
        if (resolvedTrust == null || resolvedTrust.isEmpty() || !new File(resolvedTrust).exists()) {
            throw new IllegalStateException("CRITICAL SECURITY ERROR: Missing or invalid truststore path (" + truststorePath + "). mTLS cannot be established. Failing securely.");
        }

        try {
            // Load Truststore
            KeyStore trustStore = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(resolvedTrust)) {
                trustStore.load(fis, truststorePassword.toCharArray());
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            // Load Keystore
            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(resolvedKey)) {
                keyStore.load(fis, keystorePassword.toCharArray());
            }
            
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keystorePassword.toCharArray());

            // Initialize SSLContext
            String protocol = "TLS";
            try {
                // Ensure proper standard SSL context algorithm resolution
                protocol = "TLSv1.3";
                SSLContext.getInstance(protocol);
            } catch (Exception ex) {
                protocol = "TLS";
            }
            SSLContext sslContext = SSLContext.getInstance(protocol);
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

            return sslContext;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SSLContext for TLS communication", e);
        }
    }

    /**
     * Creates an SSLContext that trusts a PEM-encoded CA certificate and does not
     * require a client certificate. This is used for local host-run MongoDB clients
     * that connect to the Docker replica set over TLS.
     *
     * @param caCertificatePath the path to the PEM CA certificate
     * @return the initialized SSLContext
     */
    public static SSLContext createTrustOnlySslContextFromPem(String caCertificatePath) {
        String resolvedCa = resolvePath(caCertificatePath);
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Certificate caCertificate;
            try (FileInputStream fis = new FileInputStream(resolvedCa)) {
                caCertificate = certificateFactory.generateCertificate(fis);
            }

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("mongo-ca", caCertificate);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            return sslContext;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create MongoDB SSLContext from PEM certificate at " + caCertificatePath, e);
        }
    }
}
