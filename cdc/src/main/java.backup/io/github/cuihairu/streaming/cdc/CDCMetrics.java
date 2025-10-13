package io.github.cuihairu.redis.streaming.cdc;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * CDC connector metrics
 */
@Data
@AllArgsConstructor
public class CDCMetrics {

    private final long totalEventsCaptured;
    private final long insertEvents;
    private final long updateEvents;
    private final long deleteEvents;
    private final long schemaChangeEvents;
    private final long snapshotRecords;
    private final long errorsCount;
    private final double averageEventLatencyMs;
    private final Instant lastEventTime;
    private final Instant lastCommitTime;
    private final Instant startTime;
    private final String currentPosition;

    public CDCMetrics() {
        this(0, 0, 0, 0, 0, 0, 0, 0.0, null, null, Instant.now(), null);
    }

    public long getDataChangeEvents() {
        return insertEvents + updateEvents + deleteEvents;
    }

    public double getEventRate() {
        if (startTime == null) return 0.0;

        long durationMs = System.currentTimeMillis() - startTime.toEpochMilli();
        if (durationMs <= 0) return 0.0;

        return (double) totalEventsCaptured / (durationMs / 1000.0); // events per second
    }

    /**
     * Create metrics with updated event counts
     */
    public CDCMetrics withEventCounts(long additionalInserts, long additionalUpdates,
                                     long additionalDeletes, long additionalSchemaChanges) {
        return new CDCMetrics(
                totalEventsCaptured + additionalInserts + additionalUpdates + additionalDeletes + additionalSchemaChanges,
                insertEvents + additionalInserts,
                updateEvents + additionalUpdates,
                deleteEvents + additionalDeletes,
                schemaChangeEvents + additionalSchemaChanges,
                snapshotRecords,
                errorsCount,
                averageEventLatencyMs,
                Instant.now(),
                lastCommitTime,
                startTime,
                currentPosition
        );
    }

    /**
     * Create metrics with updated position
     */
    public CDCMetrics withPosition(String position) {
        return new CDCMetrics(
                totalEventsCaptured,
                insertEvents,
                updateEvents,
                deleteEvents,
                schemaChangeEvents,
                snapshotRecords,
                errorsCount,
                averageEventLatencyMs,
                lastEventTime,
                lastCommitTime,
                startTime,
                position
        );
    }

    /**
     * Create metrics with commit information
     */
    public CDCMetrics withCommit() {
        return new CDCMetrics(
                totalEventsCaptured,
                insertEvents,
                updateEvents,
                deleteEvents,
                schemaChangeEvents,
                snapshotRecords,
                errorsCount,
                averageEventLatencyMs,
                lastEventTime,
                Instant.now(),
                startTime,
                currentPosition
        );
    }

    /**
     * Create metrics with error count
     */
    public CDCMetrics withError() {
        return new CDCMetrics(
                totalEventsCaptured,
                insertEvents,
                updateEvents,
                deleteEvents,
                schemaChangeEvents,
                snapshotRecords,
                errorsCount + 1,
                averageEventLatencyMs,
                lastEventTime,
                lastCommitTime,
                startTime,
                currentPosition
        );
    }

    /**
     * Create metrics with snapshot information
     */
    public CDCMetrics withSnapshot(long snapshotRecordCount) {
        return new CDCMetrics(
                totalEventsCaptured,
                insertEvents,
                updateEvents,
                deleteEvents,
                schemaChangeEvents,
                snapshotRecordCount,
                errorsCount,
                averageEventLatencyMs,
                lastEventTime,
                lastCommitTime,
                startTime,
                currentPosition
        );
    }
}