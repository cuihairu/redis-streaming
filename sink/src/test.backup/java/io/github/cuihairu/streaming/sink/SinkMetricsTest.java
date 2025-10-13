package io.github.cuihairu.redis.streaming.sink;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SinkMetricsTest {

    @Test
    void testDefaultConstructor() {
        SinkMetrics metrics = new SinkMetrics();

        assertEquals(0L, metrics.getRecordsWritten());
        assertEquals(0L, metrics.getRecordsFailed());
        assertEquals(0L, metrics.getBytesWritten());
        assertEquals(0L, metrics.getWriteRequests());
        assertEquals(0L, metrics.getFlushCount());
        assertEquals(0.0, metrics.getAverageWriteLatencyMs());
        assertNull(metrics.getLastWriteTime());
        assertNull(metrics.getLastFlushTime());
        assertNotNull(metrics.getStartTime());
    }

    @Test
    void testFullConstructor() {
        Instant now = Instant.now();
        SinkMetrics metrics = new SinkMetrics(
                100L, 5L, 1024L, 10L, 2L, 50.0, now, now, now
        );

        assertEquals(100L, metrics.getRecordsWritten());
        assertEquals(5L, metrics.getRecordsFailed());
        assertEquals(1024L, metrics.getBytesWritten());
        assertEquals(10L, metrics.getWriteRequests());
        assertEquals(2L, metrics.getFlushCount());
        assertEquals(50.0, metrics.getAverageWriteLatencyMs());
        assertEquals(now, metrics.getLastWriteTime());
        assertEquals(now, metrics.getLastFlushTime());
        assertEquals(now, metrics.getStartTime());
    }

    @Test
    void testCalculatedMethods() {
        SinkMetrics metrics = new SinkMetrics(
                80L, 20L, 1024L, 10L, 2L, 50.0,
                Instant.now(), Instant.now(), Instant.now()
        );

        assertEquals(100L, metrics.getTotalRecords());
        assertEquals(0.8, metrics.getSuccessRate(), 0.001);
        assertEquals(0.2, metrics.getErrorRate(), 0.001);
    }

    @Test
    void testSuccessRateWithZeroRecords() {
        SinkMetrics metrics = new SinkMetrics();

        assertEquals(0L, metrics.getTotalRecords());
        assertEquals(0.0, metrics.getSuccessRate());
        assertEquals(0.0, metrics.getErrorRate());
    }

    @Test
    void testWithUpdatedCounts() {
        SinkMetrics original = new SinkMetrics(
                50L, 5L, 512L, 5L, 1L, 25.0,
                Instant.now(), Instant.now(), Instant.now()
        );

        SinkMetrics updated = original.withUpdatedCounts(10L, 2L, 256L);

        assertEquals(60L, updated.getRecordsWritten());
        assertEquals(7L, updated.getRecordsFailed());
        assertEquals(768L, updated.getBytesWritten());
        assertEquals(6L, updated.getWriteRequests());
        assertEquals(1L, updated.getFlushCount()); // Unchanged
        assertNotNull(updated.getLastWriteTime());
    }

    @Test
    void testWithFlush() {
        SinkMetrics original = new SinkMetrics(
                50L, 5L, 512L, 5L, 1L, 25.0,
                Instant.now(), null, Instant.now()
        );

        SinkMetrics updated = original.withFlush();

        assertEquals(50L, updated.getRecordsWritten()); // Unchanged
        assertEquals(5L, updated.getRecordsFailed()); // Unchanged
        assertEquals(2L, updated.getFlushCount()); // Incremented
        assertNotNull(updated.getLastFlushTime());
    }

    @Test
    void testWithLatency() {
        SinkMetrics original = new SinkMetrics(
                50L, 5L, 512L, 4L, 1L, 20.0, // 4 write requests, avg 20ms
                Instant.now(), Instant.now(), Instant.now()
        );

        // New latency: 30ms
        // New average should be (20 * 4 + 30) / 5 = 110 / 5 = 22ms
        SinkMetrics updated = original.withLatency(30.0);

        assertEquals(22.0, updated.getAverageWriteLatencyMs(), 0.001);
    }

    @Test
    void testWithLatencyFirstRequest() {
        SinkMetrics original = new SinkMetrics();

        SinkMetrics updated = original.withLatency(50.0);

        assertEquals(50.0, updated.getAverageWriteLatencyMs());
    }
}