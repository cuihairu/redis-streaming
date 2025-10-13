package io.github.cuihairu.redis.streaming.sink;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Sink connector metrics
 */
@Data
@AllArgsConstructor
public class SinkMetrics {

    private final long recordsWritten;
    private final long recordsFailed;
    private final long bytesWritten;
    private final long writeRequests;
    private final long flushCount;
    private final double averageWriteLatencyMs;
    private final Instant lastWriteTime;
    private final Instant lastFlushTime;
    private final Instant startTime;

    public SinkMetrics() {
        this(0, 0, 0, 0, 0, 0.0, null, null, Instant.now());
    }

    public double getSuccessRate() {
        long total = recordsWritten + recordsFailed;
        return total > 0 ? (double) recordsWritten / total : 0.0;
    }

    public double getErrorRate() {
        long total = recordsWritten + recordsFailed;
        return total > 0 ? (double) recordsFailed / total : 0.0;
    }

    public long getTotalRecords() {
        return recordsWritten + recordsFailed;
    }

    /**
     * Create metrics with updated values
     */
    public SinkMetrics withUpdatedCounts(long additionalWritten, long additionalFailed, long additionalBytes) {
        return new SinkMetrics(
                recordsWritten + additionalWritten,
                recordsFailed + additionalFailed,
                bytesWritten + additionalBytes,
                writeRequests + 1,
                flushCount,
                averageWriteLatencyMs,
                Instant.now(),
                lastFlushTime,
                startTime
        );
    }

    /**
     * Create metrics with updated flush count
     */
    public SinkMetrics withFlush() {
        return new SinkMetrics(
                recordsWritten,
                recordsFailed,
                bytesWritten,
                writeRequests,
                flushCount + 1,
                averageWriteLatencyMs,
                lastWriteTime,
                Instant.now(),
                startTime
        );
    }

    /**
     * Create metrics with updated latency
     */
    public SinkMetrics withLatency(double latencyMs) {
        double newAverage = writeRequests > 0
            ? (averageWriteLatencyMs * writeRequests + latencyMs) / (writeRequests + 1)
            : latencyMs;

        return new SinkMetrics(
                recordsWritten,
                recordsFailed,
                bytesWritten,
                writeRequests,
                flushCount,
                newAverage,
                lastWriteTime,
                lastFlushTime,
                startTime
        );
    }
}