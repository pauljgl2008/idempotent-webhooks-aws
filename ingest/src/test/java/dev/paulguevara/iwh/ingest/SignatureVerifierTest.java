package dev.paulguevara.iwh.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.paulguevara.iwh.ingest.SignatureVerifier.Outcome;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

    static final byte[] SECRET = "test-secret-not-a-real-one".getBytes(StandardCharsets.UTF_8);
    static final long NOW = 1_793_000_000L;
    static final byte[] BODY = "{\"eventId\":\"evt-1\"}".getBytes(StandardCharsets.UTF_8);

    final SignatureVerifier verifier =
            new SignatureVerifier(SECRET, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));

    /** Computed here with the JDK directly, not with the class under test. */
    static String sign(byte[] secret, long timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    static String header(long timestamp, String hex) {
        return "t=" + timestamp + ",v1=" + hex;
    }

    @Test
    void acceptsAValidSignature() throws Exception {
        assertThat(verifier.verify(header(NOW, sign(SECRET, NOW, BODY)), BODY)).isEqualTo(Outcome.VALID);
    }

    @Test
    void acceptsTheEdgesOfTheWindow() throws Exception {
        long early = NOW - SignatureVerifier.TOLERANCE.toSeconds();
        long late = NOW + SignatureVerifier.TOLERANCE.toSeconds();

        assertThat(verifier.verify(header(early, sign(SECRET, early, BODY)), BODY)).isEqualTo(Outcome.VALID);
        assertThat(verifier.verify(header(late, sign(SECRET, late, BODY)), BODY)).isEqualTo(Outcome.VALID);
    }

    @Test
    void rejectsATimestampOutsideTheWindow() throws Exception {
        long stale = NOW - SignatureVerifier.TOLERANCE.toSeconds() - 1;

        assertThat(verifier.verify(header(stale, sign(SECRET, stale, BODY)), BODY)).isEqualTo(Outcome.STALE);
    }

    @Test
    void rejectsATamperedBody() throws Exception {
        byte[] tampered = "{\"eventId\":\"evt-2\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(header(NOW, sign(SECRET, NOW, BODY)), tampered)).isEqualTo(Outcome.MISMATCH);
    }

    @Test
    void rejectsAnotherSecret() throws Exception {
        byte[] other = "another-secret".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(header(NOW, sign(other, NOW, BODY)), BODY)).isEqualTo(Outcome.MISMATCH);
    }

    @Test
    void rejectsAReplayedSignatureWithAnotherTimestamp() throws Exception {
        String oldSignature = sign(SECRET, NOW - 10, BODY);

        assertThat(verifier.verify(header(NOW, oldSignature), BODY)).isEqualTo(Outcome.MISMATCH);
    }

    @Test
    void rejectsAMissingHeader() {
        assertThat(verifier.verify(null, BODY)).isEqualTo(Outcome.MISSING);
        assertThat(verifier.verify("  ", BODY)).isEqualTo(Outcome.MISSING);
    }

    @Test
    void rejectsMalformedHeaders() {
        for (String bad : new String[] {"v1=abc", "t=abc,v1=00", "t=1", "garbage", "t=1,v1=zz", "t=1,v1="}) {
            assertThat(verifier.verify(bad, BODY)).as(bad).isEqualTo(Outcome.MALFORMED);
        }
    }

    @Test
    void acceptsUppercaseHexAndExtraSpaces() throws Exception {
        String hex = sign(SECRET, NOW, BODY).toUpperCase();

        assertThat(verifier.verify(" t=" + NOW + " , v1=" + hex + " ", BODY)).isEqualTo(Outcome.VALID);
    }
}
