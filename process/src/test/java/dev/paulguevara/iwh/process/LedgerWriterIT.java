package dev.paulguevara.iwh.process;

import static org.assertj.core.api.Assertions.assertThat;

import dev.paulguevara.iwh.contract.ContributionRecorded;
import dev.paulguevara.iwh.process.LedgerWriter.Result;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Testcontainers
class LedgerWriterIT {

    static final String DYNAMODB_LOCAL_IMAGE = "amazon/dynamodb-local:3.3.2";
    static final int DYNAMODB_LOCAL_PORT = 8000;
    static final int PARALLEL_DELIVERIES = 8;
    static final int MAX_ATTEMPTS = 20;

    @Container
    static final GenericContainer<?> DYNAMODB =
            new GenericContainer<>(DYNAMODB_LOCAL_IMAGE).withExposedPorts(DYNAMODB_LOCAL_PORT);

    static DynamoDbClient client;
    static int run;

    Tables tables;
    LedgerWriter writer;

    @BeforeAll
    static void connect() {
        client = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(DYNAMODB_LOCAL_PORT)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @BeforeEach
    void freshTables() {
        run++;
        tables = new Tables("t" + run + "-processed", "t" + run + "-ledger", "t" + run + "-balances");
        TestTables.create(client, tables);
        writer = new LedgerWriter(client, tables);
    }

    static ContributionRecorded event(String eventId, long amount) {
        return new ContributionRecorded("acme", eventId, "emp-1", "camp-1", amount, "USD",
                Instant.parse("2026-11-01T14:00:00Z"));
    }

    long balance() {
        var item = client.getItem(r -> r.tableName(tables.balances()).consistentRead(true)
                .key(Map.of("pk", AttributeValue.fromS("emp-1#camp-1#USD")))).item();
        return item.isEmpty() ? 0 : Long.parseLong(item.get("balanceMinor").n());
    }

    int ledgerEntries() {
        return client.scan(r -> r.tableName(tables.ledger()).consistentRead(true)).count();
    }

    @Test
    void appliesANewMovement() {
        assertThat(writer.apply(event("e1", 2500))).isEqualTo(Result.APPLIED);

        assertThat(balance()).isEqualTo(2500);
        assertThat(ledgerEntries()).isEqualTo(1);
    }

    @Test
    void movementsAddUp() {
        writer.apply(event("e1", 2500));
        writer.apply(event("e2", 1000));

        assertThat(balance()).isEqualTo(3500);
        assertThat(ledgerEntries()).isEqualTo(2);
    }

    @Test
    void theSameMovementTwiceIsADuplicateAndChangesNothing() {
        writer.apply(event("e1", 2500));

        assertThat(writer.apply(event("e1", 2500))).isEqualTo(Result.DUPLICATE);
        assertThat(balance()).isEqualTo(2500);
        assertThat(ledgerEntries()).isEqualTo(1);
    }

    @Test
    void theSameIdWithOtherContentIsAConflictAndChangesNothing() {
        writer.apply(event("e1", 2500));

        assertThat(writer.apply(event("e1", 9999))).isEqualTo(Result.CONFLICT);
        assertThat(balance()).isEqualTo(2500);
        assertThat(ledgerEntries()).isEqualTo(1);
    }

    @Test
    void parallelDeliveriesOfOneMovementApplyItOnce() throws Exception {
        ContributionRecorded same = event("e-parallel", 700);
        Callable<Result> deliverUntilSettled = () -> {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                Result result = writer.apply(same);
                if (result != Result.RETRY) {
                    return result;
                }
            }
            return Result.RETRY;
        };

        List<Result> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_DELIVERIES)) {
            List<Future<Result>> futures = new ArrayList<>();
            for (int i = 0; i < PARALLEL_DELIVERIES; i++) {
                futures.add(pool.submit(deliverUntilSettled));
            }
            for (Future<Result> f : futures) {
                results.add(f.get());
            }
        }

        assertThat(results).containsOnly(Result.APPLIED, Result.DUPLICATE);
        assertThat(results).filteredOn(r -> r == Result.APPLIED).hasSize(1);
        assertThat(balance()).isEqualTo(700);
        assertThat(ledgerEntries()).isEqualTo(1);
    }

    @Test
    void storesTheHashNextToTheMarker() {
        ContributionRecorded e = event("e1", 2500);
        writer.apply(e);

        var marker = client.getItem(r -> r.tableName(tables.processedEvents()).consistentRead(true)
                .key(Map.of("pk", AttributeValue.fromS("acme#e1")))).item();

        assertThat(marker.get("payloadHash").s()).isEqualTo(e.payloadHash());
        assertThat(marker).doesNotContainKey("ttl");
    }
}
