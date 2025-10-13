package io.github.cuihairu.redis.streaming.sink;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Sink error information
 */
@Data
@AllArgsConstructor
public class SinkError {

    private final SinkRecord record;
    private final String errorMessage;
    private final Throwable cause;
    private final String errorCode;

    public SinkError(SinkRecord record, String errorMessage) {
        this(record, errorMessage, null, null);
    }

    public SinkError(SinkRecord record, String errorMessage, Throwable cause) {
        this(record, errorMessage, cause, null);
    }
}