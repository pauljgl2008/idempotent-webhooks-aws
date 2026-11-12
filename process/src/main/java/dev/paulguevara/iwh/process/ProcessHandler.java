package dev.paulguevara.iwh.process;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import dev.paulguevara.iwh.contract.ContributionRecorded;
import dev.paulguevara.iwh.contract.InvalidEventException;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * SQS consumer with ReportBatchItemFailures: only the items that must come back are
 * reported, so one bad message does not redeliver the nine good ones.
 */
public final class ProcessHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    static final String ID_CONFLICT = "IdConflict";
    static final String DUPLICATE_SKIPPED = "DuplicateSkipped";
    static final String APPLIED = "Applied";

    private final MovementWriter writer;
    private final FaultInjector faults;

    /** Used by the Lambda runtime. */
    public ProcessHandler() {
        this(new LedgerWriter(
                        DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                        Tables.fromEnvironment()),
                FaultInjector.fromEnvironment());
    }

    ProcessHandler(MovementWriter writer, FaultInjector faults) {
        this.writer = writer;
        this.faults = faults;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent batch, Context context) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        long applied = 0;
        long duplicates = 0;
        long conflicts = 0;
        for (SQSEvent.SQSMessage message : batch.getRecords()) {
            ContributionRecorded event;
            try {
                event = ContributionRecorded.parse(message.getBody());
            } catch (InvalidEventException e) {
                ProcessLog.error("invalid_event", message.getMessageId(), e.getMessage());
                failures.add(failure(message));
                continue;
            }
            LedgerWriter.Result result;
            try {
                result = writer.apply(event);
            } catch (RuntimeException e) {
                ProcessLog.error("write_error", event.idempotencyKey(), e.getClass().getSimpleName());
                failures.add(failure(message));
                continue;
            }
            switch (result) {
                case APPLIED -> {
                    applied++;
                    ProcessLog.info("applied", event.idempotencyKey());
                    // a crash here is the case the guarantee is about: committed, not acknowledged
                    faults.afterCommit(event.eventId());
                }
                case DUPLICATE -> {
                    duplicates++;
                    ProcessLog.info("duplicate", event.idempotencyKey());
                }
                case CONFLICT -> {
                    conflicts++;
                    ProcessLog.error("id_conflict", event.idempotencyKey(), "same id, different payload");
                    failures.add(failure(message));
                }
                case RETRY, FAILED -> failures.add(failure(message));
            }
        }
        emit(applied, duplicates, conflicts);
        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }

    private static SQSBatchResponse.BatchItemFailure failure(SQSEvent.SQSMessage message) {
        return SQSBatchResponse.BatchItemFailure.builder().withItemIdentifier(message.getMessageId()).build();
    }

    private static void emit(long applied, long duplicates, long conflicts) {
        long now = System.currentTimeMillis();
        ProcessLog.metric(APPLIED, applied, now);
        ProcessLog.metric(DUPLICATE_SKIPPED, duplicates, now);
        ProcessLog.metric(ID_CONFLICT, conflicts, now);
    }
}
