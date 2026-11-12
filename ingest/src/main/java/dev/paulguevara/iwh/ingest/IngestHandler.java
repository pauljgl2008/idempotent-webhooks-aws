package dev.paulguevara.iwh.ingest;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import dev.paulguevara.iwh.contract.ContributionRecorded;
import dev.paulguevara.iwh.contract.InvalidEventException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * HTTP API (payload v2) entry point. Answers 202 only after SQS confirmed the message,
 * so a 2xx always means the movement is durably queued.
 */
public final class IngestHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    static final String SIGNATURE_HEADER = "x-signature";
    static final int ACCEPTED = 202;
    static final int BAD_REQUEST = 400;
    static final int UNAUTHORIZED = 401;
    static final int SERVER_ERROR = 500;

    private static final Map<String, String> JSON_HEADERS = Map.of("content-type", "application/json");

    private final SignatureVerifier verifier;
    private final QueuePublisher publisher;

    /** Used by the Lambda runtime: reads the secret once per execution environment. */
    public IngestHandler() {
        this(fromEnvironment());
    }

    private IngestHandler(Wiring wiring) {
        this(wiring.verifier(), wiring.publisher());
    }

    IngestHandler(SignatureVerifier verifier, QueuePublisher publisher) {
        this.verifier = verifier;
        this.publisher = publisher;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent request, Context context) {
        byte[] raw;
        try {
            raw = rawBody(request);
        } catch (IllegalArgumentException e) {
            log("rejected", "undecodable_body");
            return respond(BAD_REQUEST, "{\"error\":\"invalid_event\"}");
        }
        SignatureVerifier.Outcome signature = verifier.verify(header(request, SIGNATURE_HEADER), raw);
        if (signature != SignatureVerifier.Outcome.VALID) {
            log("rejected", "signature_" + signature.name().toLowerCase());
            return respond(UNAUTHORIZED, "{\"error\":\"invalid_signature\"}");
        }
        String body = new String(raw, StandardCharsets.UTF_8);
        ContributionRecorded event;
        try {
            event = ContributionRecorded.parse(body);
        } catch (InvalidEventException e) {
            log("rejected", "invalid_event");
            return respond(BAD_REQUEST, "{\"error\":\"invalid_event\"}");
        }
        try {
            publisher.publish(body);
        } catch (RuntimeException e) {
            log("failed", "queue_unavailable");
            return respond(SERVER_ERROR, "{\"error\":\"try_again\"}");
        }
        log("accepted", null);
        return respond(ACCEPTED, "{\"accepted\":\"" + event.idempotencyKey() + "\"}");
    }

    private static byte[] rawBody(APIGatewayV2HTTPEvent request) {
        String body = request.getBody();
        if (body == null) {
            return new byte[0];
        }
        return request.getIsBase64Encoded()
                ? Base64.getDecoder().decode(body)
                : body.getBytes(StandardCharsets.UTF_8);
    }

    private static String header(APIGatewayV2HTTPEvent request, String name) {
        Map<String, String> headers = request.getHeaders();
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(h -> h.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static APIGatewayV2HTTPResponse respond(int status, String body) {
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(status)
                .withHeaders(JSON_HEADERS)
                .withBody(body)
                .build();
    }

    /** One JSON line per request; never the body, the signature or the secret. */
    private static void log(String result, String reason) {
        System.out.println(reason == null
                ? "{\"component\":\"ingest\",\"result\":\"" + result + "\"}"
                : "{\"component\":\"ingest\",\"result\":\"" + result + "\",\"reason\":\"" + reason + "\"}");
    }

    private record Wiring(SignatureVerifier verifier, QueuePublisher publisher) { }

    private static Wiring fromEnvironment() {
        String queueUrl = requiredEnv("QUEUE_URL");
        String secretParameter = requiredEnv("SECRET_PARAMETER_NAME");
        byte[] secret;
        try (SsmClient ssm = SsmClient.builder().httpClient(UrlConnectionHttpClient.create()).build()) {
            secret = ssm.getParameter(r -> r.name(secretParameter).withDecryption(true))
                    .parameter().value().getBytes(StandardCharsets.UTF_8);
        }
        SqsClient sqs = SqsClient.builder().httpClient(UrlConnectionHttpClient.create()).build();
        QueuePublisher publisher = body -> sqs.sendMessage(r -> r.queueUrl(queueUrl).messageBody(body));
        return new Wiring(new SignatureVerifier(secret, Clock.systemUTC()), publisher);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing environment variable " + name);
        }
        return value;
    }
}
