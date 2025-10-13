package io.github.cuihairu.redis.streaming.source;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Source connector health status
 */
@Data
@AllArgsConstructor
public class SourceHealthStatus {

    public enum Status {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
        UNKNOWN
    }

    private final Status status;
    private final String message;
    private final Instant timestamp;
    private final long recordsPolled;
    private final long errorsCount;

    public SourceHealthStatus(Status status, String message) {
        this(status, message, Instant.now(), 0, 0);
    }

    public static SourceHealthStatus healthy(String message) {
        return new SourceHealthStatus(Status.HEALTHY, message);
    }

    public static SourceHealthStatus degraded(String message) {
        return new SourceHealthStatus(Status.DEGRADED, message);
    }

    public static SourceHealthStatus unhealthy(String message) {
        return new SourceHealthStatus(Status.UNHEALTHY, message);
    }

    public static SourceHealthStatus unknown(String message) {
        return new SourceHealthStatus(Status.UNKNOWN, message);
    }

    public boolean isHealthy() {
        return status == Status.HEALTHY;
    }

    public boolean isUnhealthy() {
        return status == Status.UNHEALTHY;
    }
}