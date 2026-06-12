package edu.kinneret.parking.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Provides basic HMAC-SHA256 signing and verification for queue messages.
 */
public final class SecureMessageSigner {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    /**
     * Creates a signer using the {@code HMAC_SECRET} environment variable.
     */
    public SecureMessageSigner() {
        this(System.getenv());
    }

    /**
     * Creates a signer using a supplied environment map.
     *
     * @param environment the environment variable map
     */
    public SecureMessageSigner(Map<String, String> environment) {
        this(readSecret(environment));
    }

    /**
     * Creates a signer using an explicit secret value.
     *
     * @param secret the secret used for HMAC operations
     */
    public SecureMessageSigner(String secret) {
        this.secret = ValidationUtils.requireNonEmpty(secret, "secret")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Signs the provided content with HMAC-SHA256.
     *
     * @param content the content to sign
     * @return the hexadecimal HMAC string
     */
    public String sign(String content) {
        ValidationUtils.requireNonEmpty(content, "content");
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] signature = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signature);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign content with HMAC-SHA256.", ex);
        }
    }

    /**
     * Verifies that the provided content matches the expected HMAC value.
     *
     * @param content the content to verify
     * @param expectedHmac the expected hexadecimal HMAC string
     * @return {@code true} when the signature matches
     */
    public boolean verify(String content, String expectedHmac) {
        ValidationUtils.requireNonEmpty(expectedHmac, "expectedHmac");
        String actualHmac = sign(content);
        return MessageDigest.isEqual(
                actualHmac.getBytes(StandardCharsets.UTF_8),
                expectedHmac.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Resolves the HMAC signing secret from the environment, preferring a file-based
     * secret specified by {@code HMAC_SECRET_FILE} before falling back to the
     * {@code HMAC_SECRET} variable.
     *
     * @param environment the environment variable map
     * @return the resolved secret string
     * @throws IllegalStateException if no secret can be found or the file cannot be read
     */
    private static String readSecret(Map<String, String> environment) {
        String secretFile = environment.get("HMAC_SECRET_FILE");
        if (secretFile != null && !secretFile.isBlank()) {
            try {
                return java.nio.file.Files.readString(java.nio.file.Paths.get(secretFile)).trim();
            } catch (Exception ex) {
                throw new IllegalStateException("CRITICAL SECURITY ERROR: Could not read HMAC_SECRET_FILE.", ex);
            }
        }
        
        String value = environment.get("HMAC_SECRET");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("CRITICAL SECURITY ERROR: HMAC_SECRET environment variable is missing. The system cannot operate securely without a message signing key.");
        }
        return value.trim();
    }
}
