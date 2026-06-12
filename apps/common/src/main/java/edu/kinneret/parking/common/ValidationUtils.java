package edu.kinneret.parking.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Contains small validation helpers shared by the queue-server foundation.
 */
public final class ValidationUtils {
    private static final Pattern QUEUE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{1,255}$");
    private static final Pattern MESSAGE_TYPE_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{1,100}$");
    private static final Pattern VEHICLE_ID_PATTERN = Pattern.compile("^[A-Z0-9-]{1,20}$");
    private static final Pattern SPACE_ID_PATTERN = Pattern.compile("^(?:[A-Z]{0,3}\\d{1,4}|\\d{1,4})$");
    private static final Pattern REASON_PATTERN = Pattern.compile("^[a-zA-Z0-9 .,;:()#/'_-]{1,255}$");
    private static final Pattern SAFE_LABEL_PATTERN = Pattern.compile("^[a-zA-Z0-9 .,;:()#/'_-]{1,100}$");
    private static final Pattern ACTION_PATTERN = Pattern.compile("^(start|stop)$");
    private static final Pattern COST_PATTERN = Pattern.compile("^\\d{1,6}(?:\\.\\d{1,2})?$");

    /** Utility class; instantiation is not permitted. */
    private ValidationUtils() {
    }

    /**
     * Requires the provided value to be non-empty after trimming.
     *
     * @param value the value to validate
     * @param fieldName the field name used in the error message
     * @return the trimmed value
     */
    public static String requireNonEmpty(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be empty.");
        }
        return value.trim();
    }

    /**
     * Requires the provided value to stay within a maximum length.
     *
     * @param value the value to validate
     * @param maxLength the maximum allowed length
     * @param fieldName the field name used in the error message
     * @return the original value when valid
     */
    public static String requireMaxLength(String value, int maxLength, String fieldName) {
        String sanitizedValue = requireNonEmpty(value, fieldName);
        if (sanitizedValue.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must be at most " + maxLength + " characters.");
        }
        return sanitizedValue;
    }

    /**
     * Requires the provided number to be positive.
     *
     * @param value the value to validate
     * @param fieldName the field name used in the error message
     * @return the validated positive value
     */
    public static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive.");
        }
        return value;
    }

    /**
     * Requires the provided number to be positive.
     *
     * @param value the value to validate
     * @param fieldName the field name used in the error message
     * @return the validated positive value
     */
    public static long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive.");
        }
        return value;
    }

    /**
     * Requires the provided queue name to match a simple RabbitMQ-safe naming pattern.
     *
     * @param value the queue name
     * @param fieldName the field name used in the error message
     * @return the validated queue name
     */
    public static String requireValidQueueName(String value, String fieldName) {
        String sanitizedValue = requireMaxLength(value, 255, fieldName);
        if (!QUEUE_NAME_PATTERN.matcher(sanitizedValue).matches()) {
            throw new IllegalArgumentException(fieldName + " contains unsupported queue name characters.");
        }
        return sanitizedValue;
    }

    /**
     * Requires the provided message type to match a compact type token pattern.
     *
     * @param value the message type
     * @param fieldName the field name used in the error message
     * @return the validated message type
     */
    public static String requireValidMessageType(String value, String fieldName) {
        String sanitizedValue = requireMaxLength(value, 100, fieldName);
        if (!MESSAGE_TYPE_PATTERN.matcher(sanitizedValue).matches()) {
            throw new IllegalArgumentException(fieldName + " contains unsupported message type characters.");
        }
        return sanitizedValue;
    }

    /**
     * Requires the provided value to be a syntactically valid UUID string.
     *
     * @param value the UUID text
     * @param fieldName the field name used in the error message
     * @return the trimmed UUID string
     */
    public static String requireValidUuid(String value, String fieldName) {
        String sanitizedValue = requireNonEmpty(value, fieldName);
        try {
            UUID.fromString(sanitizedValue);
            return sanitizedValue;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid UUID.", ex);
        }
    }

    /**
     * Requires the provided vehicle ID to match the expected format (uppercase alphanumeric).
     *
     * @param value the vehicle identifier
     * @return the validated vehicle ID
     */
    public static String requireValidVehicleId(String value) {
        String sanitizedValue = requireMaxLength(value, 20, "vehicleId");
        if (!VEHICLE_ID_PATTERN.matcher(sanitizedValue).matches()) {
            throw new IllegalArgumentException("Invalid vehicleId format.");
        }
        return sanitizedValue;
    }

    /**
     * Requires the provided space ID to match the expected format (e.g., P101).
     *
     * @param value the space identifier
     * @return the validated space ID
     */
    public static String requireValidSpaceId(String value) {
        String sanitizedValue = requireMaxLength(value, 10, "spaceId");
        if (!SPACE_ID_PATTERN.matcher(sanitizedValue).matches()) {
            throw new IllegalArgumentException("Invalid spaceId format.");
        }
        return sanitizedValue;
    }

    /**
     * Requires the amount to be non-negative.
     *
     * @param value the amount to validate
     * @param fieldName the field name for error messaging
     * @return the validated amount
     */
    public static double requireNonNegative(double value, String fieldName) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(fieldName + " must be a finite number.");
        }
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " cannot be negative.");
        }
        return value;
    }

    /**
     * Requires the amount to be non-negative and bounded to a reasonable range.
     *
     * @param value the amount to validate
     * @param fieldName the field name for error messaging
     * @param maxAllowedAmount the maximum allowed amount
     * @return the validated amount
     */
    public static double requireAmountInRange(double value, String fieldName, double maxAllowedAmount) {
        requireNonNegative(value, fieldName);
        if (value > maxAllowedAmount) {
            throw new IllegalArgumentException(fieldName + " exceeds the maximum allowed value.");
        }
        return value;
    }

    /**
     * Requires the provided free-text reason to stay within the allowed character set.
     *
     * @param value the reason text
     * @return the validated reason
     */
    public static String requireValidReason(String value) {
        String sanitizedValue = requireMaxLength(value, 255, "reason");
        if (!REASON_PATTERN.matcher(sanitizedValue).matches()) {
            throw new IllegalArgumentException("reason contains unsupported characters.");
        }
        return sanitizedValue;
    }

    /**
     * Validates a parking payload JSON object with strict type and value checks.
     *
     * @param payload the payload JSON text
     * @param maxAllowedAmount the maximum allowed money amount
     */
    public static void validateParkingPayload(String payload, double maxAllowedAmount) {
        validateParkingPayload(payload, maxAllowedAmount, "");
    }

    /**
     * Validates a parking payload JSON object with message-type-specific required
     * fields.
     *
     * @param payload the payload JSON text
     * @param maxAllowedAmount maximum allowed money amount
     * @param messageType the envelope message type
     */
    public static void validateParkingPayload(String payload, double maxAllowedAmount, String messageType) {
        String sanitizedPayload = requireNonEmpty(payload, "payload");
        try {
            JsonElement root = JsonParser.parseString(sanitizedPayload);
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("payload must be a JSON object.");
            }

            JsonObject json = root.getAsJsonObject();
            String type = messageType == null ? "" : messageType.trim().toLowerCase();
            validateKnownFieldsOnly(json, type);
            validateRequiredFields(json, type);
            validateOptionalStringField(json, "vehicleId", ValidationUtils::requireValidVehicleId);
            validateOptionalStringField(json, "spaceId", ValidationUtils::requireValidSpaceId);
            validateOptionalStringField(json, "reason", ValidationUtils::requireValidReason);
            validateOptionalStringField(json, "areaName", value -> requirePattern(value, SAFE_LABEL_PATTERN, "areaName"));
            validateOptionalStringField(json, "type", value -> requirePattern(value, ACTION_PATTERN, "type"));
            validateOptionalStringField(json, "source", value -> requirePattern(value, SAFE_LABEL_PATTERN, "source"));
            validateOptionalStringField(json, "queue", value -> requireValidQueueName(value, "queue"));
            validateOptionalStringField(json, "cost", value -> requirePattern(value, COST_PATTERN, "cost"));
            validateOptionalStringField(json, "officer", value -> requireMaxLength(value, 50, "officer"));
            validateOptionalNumericField(json, "amount", maxAllowedAmount);
        } catch (JsonParseException ex) {
            throw new IllegalArgumentException("payload must contain valid JSON.", ex);
        }
    }

    /**
     * Ensures that a JSON object contains only fields in the permitted set for the
     * given message type. Rejects any unrecognised fields to prevent injection.
     *
     * @param json        the JSON payload object
     * @param messageType the lowercase message type (e.g., "transaction.start")
     * @throws IllegalArgumentException if an unsupported field is present
     */
    private static void validateKnownFieldsOnly(JsonObject json, String messageType) {
        java.util.Set<String> allowed = new java.util.HashSet<>(java.util.List.of(
                "vehicleId", "spaceId", "areaName", "type", "cost", "amount", "reason", "officer"));
        if (messageType.endsWith(".smoke-test")) {
            allowed.add("source");
            allowed.add("queue");
        }
        for (String fieldName : json.keySet()) {
            if (!allowed.contains(fieldName)) {
                throw new IllegalArgumentException("payload contains unsupported field: " + fieldName);
            }
        }
    }

    /**
     * Asserts that all required fields are present in the payload for the given
     * message type.
     *
     * @param json        the JSON payload object
     * @param messageType the lowercase message type
     * @throws IllegalArgumentException if a required field is missing
     */
    private static void validateRequiredFields(JsonObject json, String messageType) {
        if (messageType.endsWith(".smoke-test")) {
            requireJsonStringField(json, "source");
            requireJsonStringField(json, "queue");
            return;
        }
        if (messageType.startsWith("transaction.")) {
            requireJsonStringField(json, "vehicleId");
            requireJsonStringField(json, "spaceId");
            return;
        }
        if (messageType.startsWith("citation.")) {
            requireJsonStringField(json, "vehicleId");
            requireJsonStringField(json, "spaceId");
            requireJsonNumberField(json, "amount");
            requireJsonStringField(json, "reason");
        }
    }

    /**
     * Asserts that the specified field is present and is a JSON string primitive.
     *
     * @param json      the JSON payload object
     * @param fieldName the name of the required string field
     * @throws IllegalArgumentException if the field is absent or not a string
     */
    private static void requireJsonStringField(JsonObject json, String fieldName) {
        if (!json.has(fieldName) || !json.get(fieldName).isJsonPrimitive()
                || !json.get(fieldName).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
    }

    /**
     * Asserts that the specified field is present and is a JSON numeric primitive.
     *
     * @param json      the JSON payload object
     * @param fieldName the name of the required numeric field
     * @throws IllegalArgumentException if the field is absent or not a number
     */
    private static void requireJsonNumberField(JsonObject json, String fieldName) {
        if (!json.has(fieldName) || !json.get(fieldName).isJsonPrimitive()
                || !json.get(fieldName).getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
    }

    /**
     * Validates a string value against a compiled regex pattern.
     *
     * @param value     the value to validate
     * @param pattern   the compiled pattern that the value must match
     * @param fieldName the field name used in the error message
     * @return the validated value
     * @throws IllegalArgumentException if the value exceeds 255 chars or does not match the pattern
     */
    private static String requirePattern(String value, Pattern pattern, String fieldName) {
        String sanitizedValue = requireMaxLength(value, 255, fieldName);
        if (!pattern.matcher(sanitizedValue).matches()) {
            throw new IllegalArgumentException(fieldName + " contains unsupported characters.");
        }
        return sanitizedValue;
    }

    /**
     * Validates an optional string field in the JSON payload when present.
     * Applies the provided validator function to the field value.
     *
     * @param json      the JSON payload object
     * @param fieldName the optional field name
     * @param validator the validation function to apply
     * @throws IllegalArgumentException if the field is present but fails validation
     */
    private static void validateOptionalStringField(
            JsonObject json,
            String fieldName,
            java.util.function.Function<String, String> validator) {
        if (!json.has(fieldName)) {
            return;
        }

        JsonElement field = json.get(fieldName);
        if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(fieldName + " must be a string.");
        }
        validator.apply(field.getAsString());
    }

    /**
     * Validates an optional numeric field in the JSON payload when present.
     * Ensures the value is a finite number within the configured maximum.
     *
     * @param json             the JSON payload object
     * @param fieldName        the optional field name
     * @param maxAllowedAmount the maximum allowed amount
     * @throws IllegalArgumentException if the field is present but fails validation
     */
    private static void validateOptionalNumericField(JsonObject json, String fieldName, double maxAllowedAmount) {
        if (!json.has(fieldName)) {
            return;
        }

        JsonElement field = json.get(fieldName);
        if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(fieldName + " must be numeric.");
        }
        requireAmountInRange(field.getAsDouble(), fieldName, maxAllowedAmount);
    }

    /**
     * Asserts that a payload's length is within a safe limit to prevent DoS.
     *
     * @param payload the message payload
     * @param maxBytes the maximum allowed bytes (or characters)
     */
    public static void requireSafePayloadSize(String payload, int maxBytes) {
        if (payload != null && payload.length() > maxBytes) {
            throw new IllegalArgumentException("Payload size limit exceeded to prevent denial of service.");
        }
    }
}
