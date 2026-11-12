package dev.paulguevara.iwh.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import dev.paulguevara.iwh.scenarios.ExpectedLedger.Delivery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExpectedLedgerTest {

    private static Delivery d(String eventId, String employee, String campaign, long amount, String currency) {
        return new Delivery("prov", eventId, employee, campaign, amount, currency);
    }

    @Test
    void singleMovementIsCounted() {
        ExpectedLedger ledger = new ExpectedLedger(List.of(d("e1", "emp1", "camp1", 500, "USD")));

        assertThat(ledger.balances()).isEqualTo(Map.of("emp1#camp1#USD", 500L));
        assertThat(ledger.entryCount()).isEqualTo(1);
        assertThat(ledger.entries()).isEqualTo(Set.of("prov#e1"));
        assertThat(ledger.conflicts()).isEmpty();
        assertThat(ledger.duplicates()).isZero();
    }

    @Test
    void twoMovementsAddUp() {
        ExpectedLedger ledger = new ExpectedLedger(List.of(
                d("e1", "emp1", "camp1", 500, "USD"),
                d("e2", "emp1", "camp1", 250, "USD")));

        assertThat(ledger.balances()).isEqualTo(Map.of("emp1#camp1#USD", 750L));
        assertThat(ledger.entryCount()).isEqualTo(2);
        assertThat(ledger.entries()).isEqualTo(Set.of("prov#e1", "prov#e2"));
    }

    @Test
    void identicalRedeliveryIsADuplicateAndIgnored() {
        ExpectedLedger ledger = new ExpectedLedger(List.of(
                d("e1", "emp1", "camp1", 500, "USD"),
                d("e1", "emp1", "camp1", 500, "USD"),
                d("e1", "emp1", "camp1", 500, "USD")));

        assertThat(ledger.balances()).isEqualTo(Map.of("emp1#camp1#USD", 500L));
        assertThat(ledger.entryCount()).isEqualTo(1);
        assertThat(ledger.duplicates()).isEqualTo(2);
        assertThat(ledger.conflicts()).isEmpty();
    }

    @Test
    void sameIdWithDifferentContentIsAConflictAndFirstWins() {
        ExpectedLedger ledger = new ExpectedLedger(List.of(
                d("e1", "emp1", "camp1", 500, "USD"),
                d("e1", "emp1", "camp1", 999, "USD")));

        assertThat(ledger.balances()).isEqualTo(Map.of("emp1#camp1#USD", 500L));
        assertThat(ledger.entryCount()).isEqualTo(1);
        assertThat(ledger.conflicts()).isEqualTo(Set.of("prov#e1"));
        assertThat(ledger.duplicates()).isZero();
    }

    @Test
    void conflictingOrderMeansTheOtherContentWins() {
        ExpectedLedger ledger = new ExpectedLedger(List.of(
                d("e1", "emp1", "camp1", 999, "USD"),
                d("e1", "emp1", "camp1", 500, "USD")));

        assertThat(ledger.balances()).isEqualTo(Map.of("emp1#camp1#USD", 999L));
        assertThat(ledger.conflicts()).isEqualTo(Set.of("prov#e1"));
    }

    @Test
    void currenciesAreKeptApart() {
        ExpectedLedger ledger = new ExpectedLedger(List.of(
                d("e1", "emp1", "camp1", 500, "USD"),
                d("e2", "emp1", "camp1", 300, "PEN"),
                d("e3", "emp1", "camp1", 100, "USD")));

        assertThat(ledger.balances()).isEqualTo(Map.of("emp1#camp1#USD", 600L, "emp1#camp1#PEN", 300L));
        assertThat(ledger.entryCount()).isEqualTo(3);
    }

    @Test
    void sameEventIdFromDifferentProvidersAreDifferentMovements() {
        ExpectedLedger ledger = new ExpectedLedger(List.of(
                new Delivery("a", "e1", "emp1", "camp1", 100, "USD"),
                new Delivery("b", "e1", "emp1", "camp1", 200, "USD")));

        assertThat(ledger.balances()).isEqualTo(Map.of("emp1#camp1#USD", 300L));
        assertThat(ledger.entries()).isEqualTo(Set.of("a#e1", "b#e1"));
        assertThat(ledger.conflicts()).isEmpty();
    }

    @Test
    void deliveryOrderDoesNotChangeBalances() {
        List<Delivery> deliveries = new ArrayList<>(List.of(
                d("e1", "emp1", "camp1", 100, "USD"),
                d("e2", "emp1", "camp1", 200, "USD"),
                d("e3", "emp2", "camp1", 50, "PEN"),
                d("e4", "emp2", "camp2", 70, "USD"),
                d("e2", "emp1", "camp1", 200, "USD"),
                d("e5", "emp1", "camp1", 1, "USD")));
        Map<String, Long> expected = Map.of(
                "emp1#camp1#USD", 301L,
                "emp2#camp1#PEN", 50L,
                "emp2#camp2#USD", 70L);

        for (long seed = 0; seed < 20; seed++) {
            Collections.shuffle(deliveries, new Random(seed));
            ExpectedLedger ledger = new ExpectedLedger(deliveries);

            assertThat(ledger.balances()).isEqualTo(expected);
            assertThat(ledger.entryCount()).isEqualTo(5);
            assertThat(ledger.duplicates()).isEqualTo(1);
        }
    }
}
