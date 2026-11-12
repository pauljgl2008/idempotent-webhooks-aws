package dev.paulguevara.iwh.process;

import dev.paulguevara.iwh.contract.ContributionRecorded;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * Applies one movement with a single transaction: the processed marker, the ledger entry
 * and the balance increment commit together or not at all. That is what makes the
 * guarantee "at most once" for the DynamoDB effect without any expiry window.
 */
public final class LedgerWriter implements MovementWriter {

    public enum Result {
        /** Committed now. */
        APPLIED,
        /** Same id and same content already applied: acknowledge, nothing changes. */
        DUPLICATE,
        /** Same id with different content: never applied, the item must fail. */
        CONFLICT,
        /** Contention or throttling: fail the item so SQS redelivers it. */
        RETRY,
        /** Unexpected cancellation: fail the item and log. */
        FAILED
    }

    static final String PK = "pk";
    static final String SK = "sk";
    static final String PAYLOAD_HASH = "payloadHash";

    private final DynamoDbClient dynamo;
    private final Tables tables;
    private final Clock clock;

    public LedgerWriter(DynamoDbClient dynamo, Tables tables) {
        this(dynamo, tables, Clock.systemUTC());
    }

    LedgerWriter(DynamoDbClient dynamo, Tables tables, Clock clock) {
        this.dynamo = dynamo;
        this.tables = tables;
        this.clock = clock;
    }

    @Override
    public Result apply(ContributionRecorded event) {
        String hash = event.payloadHash();
        try {
            dynamo.transactWriteItems(r -> r.transactItems(marker(event, hash), ledgerEntry(event), balance(event)));
            return Result.APPLIED;
        } catch (TransactionCanceledException e) {
            List<String> codes = e.cancellationReasons().stream().map(CancellationReason::code).toList();
            return switch (CancellationClassifier.classify(codes)) {
                case MARKER_EXISTS -> compareWithStored(event, hash);
                case RETRYABLE -> Result.RETRY;
                case INVARIANT_VIOLATION, UNKNOWN -> {
                    ProcessLog.error("transaction_cancelled", event.idempotencyKey(), String.join(",", codesOrEmpty(codes)));
                    yield Result.FAILED;
                }
            };
        }
    }

    private Result compareWithStored(ContributionRecorded event, String hash) {
        Map<String, AttributeValue> stored = dynamo.getItem(r -> r.tableName(tables.processedEvents())
                .consistentRead(true)
                .key(Map.of(PK, AttributeValue.fromS(event.idempotencyKey())))).item();
        if (stored == null || stored.isEmpty() || !stored.containsKey(PAYLOAD_HASH)) {
            // the condition failed but the marker is not readable yet: let SQS try again
            return Result.RETRY;
        }
        return hash.equals(stored.get(PAYLOAD_HASH).s()) ? Result.DUPLICATE : Result.CONFLICT;
    }

    private TransactWriteItem marker(ContributionRecorded event, String hash) {
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(tables.processedEvents())
                .item(Map.of(
                        PK, AttributeValue.fromS(event.idempotencyKey()),
                        PAYLOAD_HASH, AttributeValue.fromS(hash),
                        "processedAt", AttributeValue.fromS(clock.instant().toString())))
                .conditionExpression("attribute_not_exists(" + PK + ")")
                .build()).build();
    }

    private TransactWriteItem ledgerEntry(ContributionRecorded event) {
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(tables.ledger())
                .item(Map.of(
                        PK, AttributeValue.fromS(event.ledgerKey()),
                        SK, AttributeValue.fromS(event.idempotencyKey()),
                        "amountMinor", AttributeValue.fromN(Long.toString(event.amountMinor())),
                        "currency", AttributeValue.fromS(event.currency()),
                        "occurredAt", AttributeValue.fromS(event.occurredAt().toString())))
                .conditionExpression("attribute_not_exists(" + PK + ")")
                .build()).build();
    }

    private TransactWriteItem balance(ContributionRecorded event) {
        return TransactWriteItem.builder().update(Update.builder()
                .tableName(tables.balances())
                .key(Map.of(PK, AttributeValue.fromS(event.balanceKey())))
                .updateExpression("ADD balanceMinor :amount, entries :one")
                .expressionAttributeValues(Map.of(
                        ":amount", AttributeValue.fromN(Long.toString(event.amountMinor())),
                        ":one", AttributeValue.fromN("1")))
                .build()).build();
    }

    private static List<String> codesOrEmpty(List<String> codes) {
        return codes.stream().map(c -> c == null ? "null" : c).toList();
    }
}
