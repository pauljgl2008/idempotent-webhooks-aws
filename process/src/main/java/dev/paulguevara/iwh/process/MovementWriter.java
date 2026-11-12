package dev.paulguevara.iwh.process;

import dev.paulguevara.iwh.contract.ContributionRecorded;

/** Applies one movement and says what happened. {@link LedgerWriter} is the DynamoDB one. */
@FunctionalInterface
public interface MovementWriter {

    LedgerWriter.Result apply(ContributionRecorded event);
}
