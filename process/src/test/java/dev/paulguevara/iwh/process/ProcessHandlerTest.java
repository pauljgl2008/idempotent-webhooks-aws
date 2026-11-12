package dev.paulguevara.iwh.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import dev.paulguevara.iwh.contract.ContributionRecorded;
import dev.paulguevara.iwh.process.LedgerWriter.Result;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessHandlerTest {

    static String body(String eventId, long amount) {
        return "{\"providerId\":\"acme\",\"eventId\":\"" + eventId + "\",\"employeeId\":\"emp-1\",\"campaignId\":\"c-1\","
                + "\"amountMinor\":" + amount + ",\"currency\":\"USD\",\"occurredAt\":\"2026-11-01T14:00:00Z\"}";
    }

    static SQSEvent batch(String... bodies) {
        List<SQSEvent.SQSMessage> records = new ArrayList<>();
        for (int i = 0; i < bodies.length; i++) {
            var m = new SQSEvent.SQSMessage();
            m.setMessageId("m" + i);
            m.setBody(bodies[i]);
            records.add(m);
        }
        var event = new SQSEvent();
        event.setRecords(records);
        return event;
    }

    static List<String> failedIds(SQSBatchResponse response) {
        return response.getBatchItemFailures().stream()
                .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier).toList();
    }

    /** Writer stub: answers per eventId, defaults to APPLIED. */
    static final class StubWriter implements MovementWriter {
        final Map<String, Result> answers = new HashMap<>();
        final List<String> applied = new ArrayList<>();

        @Override
        public Result apply(ContributionRecorded event) {
            applied.add(event.eventId());
            return answers.getOrDefault(event.eventId(), Result.APPLIED);
        }
    }

    final StubWriter writer = new StubWriter();

    @Test
    void acknowledgesAppliedAndDuplicateItems() {
        writer.answers.put("e2", Result.DUPLICATE);

        var response = new ProcessHandler(writer, FaultInjector.disabled())
                .handleRequest(batch(body("e1", 1), body("e2", 1)), null);

        assertThat(failedIds(response)).isEmpty();
    }

    @Test
    void reportsOnlyTheItemsThatMustComeBack() {
        writer.answers.put("e2", Result.CONFLICT);
        writer.answers.put("e3", Result.RETRY);
        writer.answers.put("e4", Result.FAILED);

        var response = new ProcessHandler(writer, FaultInjector.disabled()).handleRequest(
                batch(body("e1", 1), body("e2", 1), body("e3", 1), body("e4", 1), body("e5", 1)), null);

        assertThat(failedIds(response)).containsExactly("m1", "m2", "m3");
    }

    @Test
    void anInvalidBodyFailsOnlyThatItem() {
        var response = new ProcessHandler(writer, FaultInjector.disabled())
                .handleRequest(batch(body("e1", 1), "{not json", body("e3", 1)), null);

        assertThat(failedIds(response)).containsExactly("m1");
        assertThat(writer.applied).containsExactly("e1", "e3");
    }

    @Test
    void anUnexpectedExceptionFailsTheItemNotTheBatch() {
        MovementWriter exploding = event -> {
            if (event.eventId().equals("e2")) {
                throw new IllegalStateException("network");
            }
            return Result.APPLIED;
        };

        var response = new ProcessHandler(exploding, FaultInjector.disabled())
                .handleRequest(batch(body("e1", 1), body("e2", 1)), null);

        assertThat(failedIds(response)).containsExactly("m1");
    }

    @Test
    void faultInjectionThrowsAfterTheCommitOnlyOnceForEachEvent() {
        var handler = new ProcessHandler(writer, new FaultInjector("fault-"));

        assertThatThrownBy(() -> handler.handleRequest(batch(body("fault-1", 5)), null))
                .isInstanceOf(FaultInjector.InjectedFault.class);
        assertThat(writer.applied).containsExactly("fault-1");

        // the redelivery goes through; the real writer would answer DUPLICATE here
        writer.answers.put("fault-1", Result.DUPLICATE);
        var response = handler.handleRequest(batch(body("fault-1", 5)), null);

        assertThat(failedIds(response)).isEmpty();
        assertThat(writer.applied).containsExactly("fault-1", "fault-1");
    }

    @Test
    void faultInjectionIgnoresOtherEvents() {
        var response = new ProcessHandler(writer, new FaultInjector("fault-"))
                .handleRequest(batch(body("normal-1", 5)), null);

        assertThat(failedIds(response)).isEmpty();
    }

    @Test
    void anEmptyPrefixDisablesInjection() {
        assertThat(new FaultInjector("").enabled()).isFalse();
        assertThat(new FaultInjector(null).enabled()).isFalse();
    }
}
