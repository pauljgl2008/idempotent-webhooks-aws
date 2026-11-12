package dev.paulguevara.iwh.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IngestHandlerTest {

    static final long NOW = SignatureVerifierTest.NOW;
    static final byte[] SECRET = SignatureVerifierTest.SECRET;
    static final String BODY = """
            {"providerId":"acme-payroll","eventId":"evt-001","employeeId":"emp-42",
             "campaignId":"camp-7","amountMinor":2500,"currency":"USD",
             "occurredAt":"2026-11-01T14:00:00Z"}""";

    final List<String> published = new ArrayList<>();
    final IngestHandler handler = new IngestHandler(
            new SignatureVerifier(SECRET, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC)),
            published::add);

    static APIGatewayV2HTTPEvent request(String body, String signature, boolean base64) {
        var event = new APIGatewayV2HTTPEvent();
        event.setBody(base64 ? Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)) : body);
        event.setIsBase64Encoded(base64);
        event.setHeaders(signature == null ? Map.of() : Map.of("x-signature", signature));
        return event;
    }

    static String signatureFor(String body) throws Exception {
        return SignatureVerifierTest.header(NOW,
                SignatureVerifierTest.sign(SECRET, NOW, body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsASignedEventAndPublishesTheRawBody() throws Exception {
        var response = handler.handleRequest(request(BODY, signatureFor(BODY), false), null);

        assertThat(response.getStatusCode()).isEqualTo(202);
        assertThat(published).containsExactly(BODY);
    }

    @Test
    void verifiesTheDecodedBytesWhenTheBodyIsBase64() throws Exception {
        var response = handler.handleRequest(request(BODY, signatureFor(BODY), true), null);

        assertThat(response.getStatusCode()).isEqualTo(202);
        assertThat(published).containsExactly(BODY);
    }

    @Test
    void returns401WithoutPublishingWhenTheSignatureIsBad() throws Exception {
        String other = BODY.replace("2500", "9999");

        var response = handler.handleRequest(request(other, signatureFor(BODY), false), null);

        assertThat(response.getStatusCode()).isEqualTo(401);
        assertThat(published).isEmpty();
    }

    @Test
    void returns401WhenTheHeaderIsMissing() {
        var response = handler.handleRequest(request(BODY, null, false), null);

        assertThat(response.getStatusCode()).isEqualTo(401);
        assertThat(published).isEmpty();
    }

    @Test
    void findsTheHeaderRegardlessOfCase() throws Exception {
        var event = request(BODY, null, false);
        event.setHeaders(Map.of("X-Signature", signatureFor(BODY)));

        assertThat(handler.handleRequest(event, null).getStatusCode()).isEqualTo(202);
    }

    @Test
    void returns400ForASignedButInvalidEvent() throws Exception {
        String invalid = "{\"eventId\":\"evt-001\"}";

        var response = handler.handleRequest(request(invalid, signatureFor(invalid), false), null);

        assertThat(response.getStatusCode()).isEqualTo(400);
        assertThat(published).isEmpty();
    }

    @Test
    void returns400WhenTheBase64BodyCannotBeDecoded() {
        var event = request(BODY, "t=1,v1=00", false);
        event.setBody("%%% not base64 %%%");
        event.setIsBase64Encoded(true);

        assertThat(handler.handleRequest(event, null).getStatusCode()).isEqualTo(400);
        assertThat(published).isEmpty();
    }

    @Test
    void returns500WhenTheQueueFailsSoTheProviderRetries() throws Exception {
        var failing = new IngestHandler(
                new SignatureVerifier(SECRET, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC)),
                body -> { throw new IllegalStateException("SQS unavailable"); });

        var response = failing.handleRequest(request(BODY, signatureFor(BODY), false), null);

        assertThat(response.getStatusCode()).isEqualTo(500);
    }
}
