package dev.paulguevara.iwh.ingest;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies {@code X-Signature: t=<epochSeconds>,v1=<hex HMAC-SHA256>} computed over
 * {@code t + "." + rawBody}. The timestamp belongs to the signature, not to the event:
 * a late retry from the provider signs again and keeps its eventId.
 */
public final class SignatureVerifier {

    public enum Outcome { VALID, MISSING, MALFORMED, STALE, MISMATCH }

    public static final Duration TOLERANCE = Duration.ofMinutes(5);

    private static final String ALGORITHM = "HmacSHA256";
    private static final int SHA256_HEX_LENGTH = 64;
    private static final Pattern DIGITS = Pattern.compile("\\d{1,18}");
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{" + SHA256_HEX_LENGTH + "}");

    private final SecretKeySpec key;
    private final Clock clock;

    public SignatureVerifier(byte[] secret, Clock clock) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("secret must not be empty");
        }
        this.key = new SecretKeySpec(secret.clone(), ALGORITHM);
        this.clock = clock;
    }

    public Outcome verify(String header, byte[] rawBody) {
        if (header == null || header.isBlank()) {
            return Outcome.MISSING;
        }
        String timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) {
                return Outcome.MALFORMED;
            }
            switch (pair[0].trim()) {
                case "t" -> timestamp = pair[1].trim();
                case "v1" -> signature = pair[1].trim();
                default -> { /* unknown schemes are ignored, as providers add versions */ }
            }
        }
        if (timestamp == null || signature == null
                || !DIGITS.matcher(timestamp).matches() || !HEX.matcher(signature).matches()) {
            return Outcome.MALFORMED;
        }
        long signedAt = Long.parseLong(timestamp);
        if (Math.abs(clock.instant().getEpochSecond() - signedAt) > TOLERANCE.toSeconds()) {
            return Outcome.STALE;
        }
        byte[] expected = hmac(timestamp, rawBody);
        byte[] received = HexFormat.of().parseHex(signature.toLowerCase());
        return MessageDigest.isEqual(expected, received) ? Outcome.VALID : Outcome.MISMATCH;
    }

    private byte[] hmac(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
