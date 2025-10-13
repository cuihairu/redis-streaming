package io.github.cuihairu.redis.streaming.reliability;

import lombok.Data;

import java.io.Serializable;

/**
 * Represents a failed element that was sent to the dead letter queue.
 *
 * @param <T> The type of the failed element
 */
@Data
public class FailedElement<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final T element;
    private final Exception exception;
    private final long timestamp;
    private final int attemptCount;
    private final String errorMessage;

    public FailedElement(T element, Exception exception, int attemptCount) {
        this.element = element;
        this.exception = exception;
        this.attemptCount = attemptCount;
        this.timestamp = System.currentTimeMillis();
        String msg = exception != null ? exception.getMessage() : null;
        this.errorMessage = (msg != null && !msg.isEmpty()) ? msg : "Unknown error";
    }

    /**
     * Get the exception stack trace as a string
     *
     * @return The stack trace
     */
    public String getStackTrace() {
        if (exception == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(exception.toString()).append("\n");
        for (StackTraceElement element : exception.getStackTrace()) {
            sb.append("\tat ").append(element.toString()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Check if this element can be retried
     *
     * @param maxAttempts Maximum allowed attempts
     * @return true if can be retried
     */
    public boolean canRetry(int maxAttempts) {
        return attemptCount < maxAttempts;
    }

    @Override
    public String toString() {
        return "FailedElement{" +
                "element=" + element +
                ", errorMessage='" + errorMessage + '\'' +
                ", attemptCount=" + attemptCount +
                ", timestamp=" + timestamp +
                '}';
    }
}
