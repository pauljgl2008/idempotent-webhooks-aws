package dev.paulguevara.iwh.contract;

/** The body is not a valid {@code contribution.recorded} event. Retrying will not fix it. */
public final class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
