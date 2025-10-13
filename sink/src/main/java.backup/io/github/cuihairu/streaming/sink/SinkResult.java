package io.github.cuihairu.redis.streaming.sink;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Result of a sink write operation
 */
@Data
@AllArgsConstructor
public class SinkResult {

    public enum Status {
        SUCCESS,
        PARTIAL_SUCCESS,
        FAILURE
    }

    private final Status status;
    private final int recordsWritten;
    private final int recordsFailed;
    private final Instant timestamp;
    private final String message;
    private final List<SinkError> errors;

    public SinkResult(Status status, int recordsWritten, int recordsFailed) {
        this(status, recordsWritten, recordsFailed, Instant.now(), null, null);
    }

    public SinkResult(Status status, int recordsWritten, String message) {
        this(status, recordsWritten, 0, Instant.now(), message, null);
    }

    public static SinkResult success(int recordsWritten) {
        return new SinkResult(Status.SUCCESS, recordsWritten, 0);
    }

    public static SinkResult success(int recordsWritten, String message) {
        return new SinkResult(Status.SUCCESS, recordsWritten, message);
    }

    public static SinkResult partialSuccess(int recordsWritten, int recordsFailed, List<SinkError> errors) {
        return new SinkResult(Status.PARTIAL_SUCCESS, recordsWritten, recordsFailed, Instant.now(),
                "Partial success", errors);
    }

    public static SinkResult failure(String message) {
        return new SinkResult(Status.FAILURE, 0, message);
    }

    public static SinkResult failure(int recordsFailed, String message, List<SinkError> errors) {
        return new SinkResult(Status.FAILURE, 0, recordsFailed, Instant.now(), message, errors);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isFailure() {
        return status == Status.FAILURE;
    }

    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }
}