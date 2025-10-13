package io.github.cuihairu.redis.streaming.sink;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Sink connector health status
 */
@Data
@AllArgsConstructor
public class SinkHealthStatus {

    public enum Status {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
        UNKNOWN
    }

    private final Status status;
    private final String message;
    private final Instant timestamp;
    private final long recordsWritten;
    private final long errorsCount;

    public SinkHealthStatus(Status status, String message) {
        this(status, message, Instant.now(), 0, 0);
    }

    public static SinkHealthStatus healthy(String message) {
        return new SinkHealthStatus(Status.HEALTHY, message);
    }

    public static SinkHealthStatus degraded(String message) {
        return new SinkHealthStatus(Status.DEGRADED, message);
    }

    public static SinkHealthStatus unhealthy(String message) {
        return new SinkHealthStatus(Status.UNHEALTHY, message);
    }

    public static SinkHealthStatus unknown(String message) {
        return new SinkHealthStatus(Status.UNKNOWN, message);
    }

    public boolean isHealthy() {
        return status == Status.HEALTHY;
    }

    public boolean isUnhealthy() {
        return status == Status.UNHEALTHY;
    }
}