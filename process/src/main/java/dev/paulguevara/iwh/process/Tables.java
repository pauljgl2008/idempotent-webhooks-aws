package dev.paulguevara.iwh.process;

/** DynamoDB table names, injected by Terraform as environment variables. */
public record Tables(String processedEvents, String ledger, String balances) {

    public static Tables fromEnvironment() {
        return new Tables(
                required("PROCESSED_EVENTS_TABLE"),
                required("LEDGER_TABLE"),
                required("BALANCES_TABLE"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing environment variable " + name);
        }
        return value;
    }
}
