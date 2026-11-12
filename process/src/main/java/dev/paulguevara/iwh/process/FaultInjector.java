package dev.paulguevara.iwh.process;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scenario-suite only. When {@code FAULT_AFTER_COMMIT_EVENT_PREFIX} is set, the handler
 * crashes right <em>after</em> the transaction committed for an eventId with that prefix,
 * once per execution environment, so the scenario proves the redelivery does not duplicate.
 * Unset in a normal deploy.
 */
public final class FaultInjector {

    public static final class InjectedFault extends RuntimeException {
        InjectedFault(String eventId) {
            super("injected failure after commit for " + eventId);
        }
    }

    private final String prefix;
    private final Set<String> fired = ConcurrentHashMap.newKeySet();

    public FaultInjector(String prefix) {
        this.prefix = prefix == null || prefix.isBlank() ? null : prefix;
    }

    public static FaultInjector disabled() {
        return new FaultInjector(null);
    }

    public static FaultInjector fromEnvironment() {
        return new FaultInjector(System.getenv("FAULT_AFTER_COMMIT_EVENT_PREFIX"));
    }

    public boolean enabled() {
        return prefix != null;
    }

    /** Called only after a commit: throws the first time it sees a matching eventId. */
    void afterCommit(String eventId) {
        if (enabled() && eventId.startsWith(prefix) && fired.add(eventId)) {
            throw new InjectedFault(eventId);
        }
    }
}
