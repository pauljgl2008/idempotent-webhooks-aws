package dev.paulguevara.iwh.scenarios;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent model of what the ledger must contain after a list of deliveries.
 * It shares no code with the system under test: it only applies the stated rule.
 *
 * <p>Rule: the first delivery of a (providerId, eventId) counts. A later delivery with the
 * same id and identical content is a duplicate and is ignored. A later one with the same id
 * and different content is a conflict: ignored, and its id is reported.
 */
public final class ExpectedLedger {

    public record Delivery(
            String providerId,
            String eventId,
            String employeeId,
            String campaignId,
            long amountMinor,
            String currency) {

        String id() {
            return providerId + "#" + eventId;
        }

        String balanceKey() {
            return employeeId + "#" + campaignId + "#" + currency;
        }
    }

    private final Map<String, Delivery> firstByEventKey = new HashMap<>();
    private final Map<String, Long> balances = new HashMap<>();
    private final Set<String> conflicts = new HashSet<>();
    private int duplicates = 0;

    public ExpectedLedger(List<Delivery> deliveries) {
        for (Delivery delivery : deliveries) {
            apply(delivery);
        }
    }

    private void apply(Delivery delivery) {
        String id = delivery.id();
        Delivery first = firstByEventKey.get(id);
        if (first == null) {
            firstByEventKey.put(id, delivery);
            long current = balances.getOrDefault(delivery.balanceKey(), 0L);
            balances.put(delivery.balanceKey(), current + delivery.amountMinor());
        } else if (first.equals(delivery)) {
            duplicates = duplicates + 1;
        } else {
            conflicts.add(id);
        }
    }

    /** Running balance per "employeeId#campaignId#currency". */
    public Map<String, Long> balances() {
        return Map.copyOf(balances);
    }

    /** How many movements were applied (one per distinct id, first delivery). */
    public int entryCount() {
        return firstByEventKey.size();
    }

    /** The "providerId#eventId" of every applied movement. */
    public Set<String> entries() {
        return Set.copyOf(firstByEventKey.keySet());
    }

    /** Ids that were delivered again with different content. */
    public Set<String> conflicts() {
        return Set.copyOf(conflicts);
    }

    /** Redeliveries with identical content, ignored. */
    public int duplicates() {
        return duplicates;
    }
}
