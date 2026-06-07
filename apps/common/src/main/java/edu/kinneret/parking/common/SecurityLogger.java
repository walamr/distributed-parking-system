package edu.kinneret.parking.common;

import java.io.File;
import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Utility for persistent security event logging.
 * Logs are stored in a dedicated file to comply with Hardening R1-Secure-Logging.
 */
public final class SecurityLogger {
    private static final Logger logger = Logger.getLogger("edu.kinneret.security");
    private static boolean initialized = false;

    private SecurityLogger() {}

    /**
     * Initializes the security logger with a persistent file handler.
     * 
     * @param logPath the path to the log file (e.g., "logs/security.log")
     */
    public static synchronized void initialize(String logPath) {
        if (initialized) return;

        try {
            File logFile = new File(logPath);
            File logDir = logFile.getParentFile();
            if (logDir != null && !logDir.exists()) {
                logDir.mkdirs();
            }

            // 5MB limit per file, 5 rotated logs, append mode
            FileHandler fileHandler = new FileHandler(logPath, 5242880, 5, true);
            fileHandler.setFormatter(new SimpleFormatter());
            fileHandler.setLevel(Level.INFO);
            
            logger.setUseParentHandlers(true); // Also log to console for visibility
            logger.addHandler(fileHandler);
            logger.setLevel(Level.INFO);
            
            initialized = true;
            logger.info("Security Logger initialized at: " + logPath);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to initialize persistent security logging.", e);
            System.err.println("CRITICAL: Failed to initialize security logging. See application logs for details.");
        }
    }

    /**
     * Logs a security event with warning level.
     * 
     * @param event the event description
     */
    public static void logSecurityEvent(String event) {
        logger.warning("[SECURITY] " + sanitize(event));
    }

    /**
     * Logs a validation rejection.
     * 
     * @param queue the queue name
     * @param reason the rejection reason
     */
    public static void logRejection(String queue, String reason) {
        logger.warning("[REJECTION] Queue: " + sanitize(queue) + " | Reason: " + sanitize(reason));
    }

    /**
     * Logs a successful high-privilege or security-relevant operation.
     * 
     * @param operation the operation description
     */
    public static void logAudit(String operation) {
        logger.info("[AUDIT] " + sanitize(operation));
    }

    /**
     * Redacts secrets from messages before they are written to logs.
     *
     * @param raw the message to sanitize
     * @return a redacted message
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String sanitized = raw;
        sanitized = sanitized.replaceAll("(?i)(password|pwd|secret|token|key)=([^\\s;&]+)", "$1=<redacted>");
        sanitized = sanitized.replaceAll("mongodb://([^:@/\\s]+):([^@/\\s]+)@", "mongodb://<redacted>:<redacted>@");
        sanitized = sanitized.replaceAll("amqps?://([^:@/\\s]+):([^@/\\s]+)@", "amqp://<redacted>:<redacted>@");
        sanitized = sanitized.replaceAll("(?i)(HMAC_SECRET|RABBITMQ_PASSWORD|MONGO_URI)=[^\\s]+", "$1=<redacted>");
        return sanitized;
    }
}

