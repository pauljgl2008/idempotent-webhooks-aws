package dev.paulguevara.iwh.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * An incremental monetary movement: one payroll deduction for one employee and campaign.
 * Sums commute, so the order in which events arrive does not matter; applying one twice does.
 */
public record ContributionRecorded(
        String providerId,
        String eventId,
        String employeeId,
        String campaignId,
        long amountMinor,
        String currency,
        Instant occurredAt) {

    public static final long MIN_AMOUNT_MINOR = 1;
    public static final long MAX_AMOUNT_MINOR = 1_000_000_000L;
    public static final String KEY_SEPARATOR = "#";

    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_EVENT_ID_LENGTH = 128;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final List<String> FIELDS = List.of(
            "providerId", "eventId", "employeeId", "campaignId", "amountMinor", "currency", "occurredAt");
    private static final ObjectMapper JSON = new ObjectMapper();

    public static ContributionRecorded parse(String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("body is not JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new InvalidEventException("body is not a JSON object");
        }
        root.fieldNames().forEachRemaining(name -> {
            if (!FIELDS.contains(name)) {
                throw new InvalidEventException("unknown field: " + name);
            }
        });
        return new ContributionRecorded(
                id(root, "providerId", MAX_ID_LENGTH),
                id(root, "eventId", MAX_EVENT_ID_LENGTH),
                id(root, "employeeId", MAX_ID_LENGTH),
                id(root, "campaignId", MAX_ID_LENGTH),
                amount(root),
                currency(root),
                instant(root));
    }

    /** {@code providerId#eventId}: what makes a delivery the same movement. */
    public String idempotencyKey() {
        return providerId + KEY_SEPARATOR + eventId;
    }

    /** {@code employeeId#campaignId}: partition of the ledger. */
    public String ledgerKey() {
        return employeeId + KEY_SEPARATOR + campaignId;
    }

    /** {@code employeeId#campaignId#currency}: one running balance per currency. */
    public String balanceKey() {
        return ledgerKey() + KEY_SEPARATOR + currency;
    }

    /** SHA-256 (hex) of the canonical form; independent of JSON key order and whitespace. */
    public String payloadHash() {
        String canonical = String.join("\n",
                "providerId=" + providerId,
                "eventId=" + eventId,
                "employeeId=" + employeeId,
                "campaignId=" + campaignId,
                "amountMinor=" + amountMinor,
                "currency=" + currency,
                "occurredAt=" + occurredAt);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            throw new InvalidEventException(field + " must be a string");
        }
        return node.asText();
    }

    private static String id(JsonNode root, String field, int maxLength) {
        String value = text(root, field);
        if (value.length() > maxLength || !ID.matcher(value).matches()) {
            throw new InvalidEventException(field + " must be 1-" + maxLength + " characters of [A-Za-z0-9_-]");
        }
        return value;
    }

    private static long amount(JsonNode root) {
        JsonNode node = root.get("amountMinor");
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new InvalidEventException("amountMinor must be an integer");
        }
        long value = node.asLong();
        if (value < MIN_AMOUNT_MINOR || value > MAX_AMOUNT_MINOR) {
            throw new InvalidEventException(
                    "amountMinor must be between " + MIN_AMOUNT_MINOR + " and " + MAX_AMOUNT_MINOR);
        }
        return value;
    }

    private static String currency(JsonNode root) {
        String value = text(root, "currency");
        if (!CURRENCY.matcher(value).matches()) {
            throw new InvalidEventException("currency must be an ISO 4217 code");
        }
        return value;
    }

    private static Instant instant(JsonNode root) {
        try {
            return Instant.parse(text(root, "occurredAt"));
        } catch (DateTimeParseException e) {
            throw new InvalidEventException("occurredAt must be an ISO-8601 instant", e);
        }
    }
}
