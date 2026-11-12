package dev.paulguevara.iwh.process;

import java.util.List;
import java.util.Set;

/**
 * Reads the {@code CancellationReasons} of a cancelled transaction. The codes come in the
 * order of the transaction items: index 0 is the processed marker, index 1 the ledger
 * entry, index 2 the balance. A cancellation is never acknowledged blindly.
 */
public final class CancellationClassifier {

    public enum Verdict {
        /** The marker exists: same id seen before. Compare hashes to tell duplicate from conflict. */
        MARKER_EXISTS,
        /** Contention or throttling: fail the item so SQS redelivers it. */
        RETRYABLE,
        /** A ledger entry exists without its marker: should be impossible. Fail and log. */
        INVARIANT_VIOLATION,
        /** Anything else: fail and log. */
        UNKNOWN
    }

    static final int MARKER = 0;
    static final int LEDGER = 1;

    private static final String CONDITION_FAILED = "ConditionalCheckFailed";
    private static final Set<String> RETRYABLE_CODES =
            Set.of("TransactionConflict", "ThrottlingError", "ProvisionedThroughputExceeded");

    private CancellationClassifier() { }

    public static Verdict classify(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Verdict.UNKNOWN;
        }
        if (CONDITION_FAILED.equals(codeAt(codes, MARKER))) {
            return Verdict.MARKER_EXISTS;
        }
        if (CONDITION_FAILED.equals(codeAt(codes, LEDGER))) {
            return Verdict.INVARIANT_VIOLATION;
        }
        if (codes.stream().anyMatch(code -> code != null && RETRYABLE_CODES.contains(code))) {
            return Verdict.RETRYABLE;
        }
        return Verdict.UNKNOWN;
    }

    private static String codeAt(List<String> codes, int index) {
        return index < codes.size() ? codes.get(index) : null;
    }
}
