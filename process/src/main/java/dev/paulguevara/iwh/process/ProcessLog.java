package dev.paulguevara.iwh.process;

/**
 * Structured logs and CloudWatch EMF metrics written to stdout. The metric dimension is
 * only the service name: an eventId dimension would create one metric per event.
 */
final class ProcessLog {

    static final String NAMESPACE = "iwh";
    static final String SERVICE = "process";

    private ProcessLog() { }

    static void info(String result, String idempotencyKey) {
        System.out.println("{\"component\":\"process\",\"level\":\"info\",\"result\":\"" + result
                + "\",\"key\":\"" + idempotencyKey + "\"}");
    }

    static void error(String result, String idempotencyKey, String detail) {
        System.out.println("{\"component\":\"process\",\"level\":\"error\",\"result\":\"" + result
                + "\",\"key\":\"" + idempotencyKey + "\",\"detail\":\"" + escape(detail) + "\"}");
    }

    /** One EMF document counting {@code value} of {@code metric}. */
    static void metric(String metric, long value, long epochMillis) {
        System.out.println("{\"_aws\":{\"Timestamp\":" + epochMillis + ",\"CloudWatchMetrics\":[{\"Namespace\":\""
                + NAMESPACE + "\",\"Dimensions\":[[\"Service\"]],\"Metrics\":[{\"Name\":\"" + metric
                + "\",\"Unit\":\"Count\"}]}]},\"Service\":\"" + SERVICE + "\",\"" + metric + "\":" + value + "}");
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
