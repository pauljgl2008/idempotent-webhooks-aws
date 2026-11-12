package dev.paulguevara.iwh.ingest;

/** Sends one message and returns only once the queue confirmed it; throws otherwise. */
@FunctionalInterface
public interface QueuePublisher {

    void publish(String body);
}
