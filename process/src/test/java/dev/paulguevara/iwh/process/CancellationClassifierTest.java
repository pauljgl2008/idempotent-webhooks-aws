package dev.paulguevara.iwh.process;

import static dev.paulguevara.iwh.process.CancellationClassifier.Verdict.INVARIANT_VIOLATION;
import static dev.paulguevara.iwh.process.CancellationClassifier.Verdict.MARKER_EXISTS;
import static dev.paulguevara.iwh.process.CancellationClassifier.Verdict.RETRYABLE;
import static dev.paulguevara.iwh.process.CancellationClassifier.Verdict.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reason codes in the order of the transaction: marker, ledger entry, balance. */
class CancellationClassifierTest {

    static List<String> reasons(String... codes) {
        return Arrays.asList(codes);
    }

    @Test
    void markerAlreadyThereMeansTheIdWasSeen() {
        assertThat(CancellationClassifier.classify(reasons("ConditionalCheckFailed", "None", "None")))
                .isEqualTo(MARKER_EXISTS);
    }

    @Test
    void markerAndLedgerBothPresentIsStillTheSameIdSeenBefore() {
        assertThat(CancellationClassifier.classify(reasons("ConditionalCheckFailed", "ConditionalCheckFailed", "None")))
                .isEqualTo(MARKER_EXISTS);
    }

    @Test
    void ledgerEntryWithoutMarkerBreaksTheInvariant() {
        assertThat(CancellationClassifier.classify(reasons("None", "ConditionalCheckFailed", "None")))
                .isEqualTo(INVARIANT_VIOLATION);
    }

    @Test
    void concurrentWritersAndThrottlingAreRetried() {
        assertThat(CancellationClassifier.classify(reasons("TransactionConflict", "None", "None")))
                .isEqualTo(RETRYABLE);
        assertThat(CancellationClassifier.classify(reasons("None", "None", "TransactionConflict")))
                .isEqualTo(RETRYABLE);
        assertThat(CancellationClassifier.classify(reasons("ThrottlingError", "None", "None")))
                .isEqualTo(RETRYABLE);
        assertThat(CancellationClassifier.classify(reasons("None", "ProvisionedThroughputExceeded", "None")))
                .isEqualTo(RETRYABLE);
    }

    @Test
    void aConflictOnAnotherItemDoesNotHideAMarkerHit() {
        // the marker check failed: the id exists, regardless of what else happened
        assertThat(CancellationClassifier.classify(reasons("ConditionalCheckFailed", "None", "TransactionConflict")))
                .isEqualTo(MARKER_EXISTS);
    }

    @Test
    void anythingElseIsUnknownAndNeverAcknowledged() {
        assertThat(CancellationClassifier.classify(reasons("ValidationError", "None", "None"))).isEqualTo(UNKNOWN);
        assertThat(CancellationClassifier.classify(reasons("None", "None", "None"))).isEqualTo(UNKNOWN);
        assertThat(CancellationClassifier.classify(List.of())).isEqualTo(UNKNOWN);
        assertThat(CancellationClassifier.classify(null)).isEqualTo(UNKNOWN);
        assertThat(CancellationClassifier.classify(reasons(null, null, null))).isEqualTo(UNKNOWN);
    }
}
