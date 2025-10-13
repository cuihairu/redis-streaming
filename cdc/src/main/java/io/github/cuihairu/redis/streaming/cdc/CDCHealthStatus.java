package io.github.cuihairu.redis.streaming.cdc;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * CDC connector health status
 */
@Data
@AllArgsConstructor
public class CDCHealthStatus {

    public enum Status {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
        UNKNOWN
    }

    private final Status status;
    private final String message;
    private final Instant timestamp;
    private final long eventsCaptured;
    private final long errorsCount;
    private final String currentPosition;

    public CDCHealthStatus(Status status, String message) {
        this(status, message, Instant.now(), 0, 0, null);
    }

    public static CDCHealthStatus healthy(String message) {
        return new CDCHealthStatus(Status.HEALTHY, message);
    }

    public static CDCHealthStatus degraded(String message) {
        return new CDCHealthStatus(Status.DEGRADED, message);
    }

    public static CDCHealthStatus unhealthy(String message) {
        return new CDCHealthStatus(Status.UNHEALTHY, message);
    }

    public static CDCHealthStatus unknown(String message) {
        return new CDCHealthStatus(Status.UNKNOWN, message);
    }

    public boolean isHealthy() {
        return status == Status.HEALTHY;
    }

    public boolean isUnhealthy() {
        return status == Status.UNHEALTHY;
    }
}