package edu.kinneret.parking.common;

import java.io.File;
import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility for persistent security event logging.
 * Logs are stored in a dedicated file to comply with Hardening R1-Secure-Logging.
 */
public final class SecurityLogger {
    private static final Logger logger = Logger.getLogger("edu.kinneret.security");
    private static boolean initialized = false;

    /** Utility class; instantiation is not permitted. */
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
            fileHandler.setFormatter(new java.util.logging.Formatter() {
                @Override
                public String format(java.util.logging.LogRecord record) {
                    String rawMsg = formatMessage(record);
                    String sanitized = sanitize(rawMsg);
                    String level = record.getLevel().toString();
                    String time = java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochMilli(record.getMillis()));
                    
                    StringBuilder json = new StringBuilder();
                    json.append("{");
                    json.append("\"timestamp\":\"").append(time).append("\",");
                    json.append("\"level\":\"").append(level).append("\",");
                    
                    if (sanitized.contains("event=") && sanitized.contains("source=")) {
                        String[] parts = sanitized.split(" ");
                        for (int i = 0; i < parts.length; i++) {
                            String[] kv = parts[i].split("=", 2);
                            if (kv.length == 2) {
                                String key = kv[0].trim();
                                String value = kv[1].trim();
                                json.append("\"").append(escapeJson(key)).append("\":\"").append(escapeJson(value)).append("\"");
                                if (i < parts.length - 1) {
                                    json.append(",");
                                }
                            }
                        }
                    } else if (sanitized.startsWith("[REJECTION]")) {
                        json.append("\"event\":\"REJECTION\",");
                        String details = sanitized.substring("[REJECTION]".length()).trim();
                        String queue = "";
                        String reason = "";
                        if (details.contains("Queue:") && details.contains("Reason:")) {
                            int qIdx = details.indexOf("Queue:");
                            int rIdx = details.indexOf("| Reason:");
                            if (rIdx > qIdx) {
                                queue = details.substring(qIdx + 6, rIdx).trim();
                                reason = details.substring(rIdx + 9).trim();
                            }
                        }
                        json.append("\"queue\":\"").append(escapeJson(queue)).append("\",");
                        json.append("\"reason\":\"").append(escapeJson(reason)).append("\",");
                        json.append("\"message\":\"").append(escapeJson(sanitized)).append("\"");
                    } else {
                        json.append("\"message\":\"").append(escapeJson(sanitized)).append("\"");
                    }
                    json.append("}\n");
                    return json.toString();
                }
            });
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
     * Escapes special JSON characters in a string so it can be safely embedded
     * within a JSON string value.
     *
     * @param str the raw string to escape
     * @return the JSON-safe escaped string
     */
    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\b", "\\b")
                  .replace("\f", "\\f")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
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

