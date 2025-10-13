package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

class CDCMetricsTest {

    private CDCMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new CDCMetrics();
    }

    @Test
    void testDefaultConstructor() {
        assertEquals(0, metrics.getTotalEventsCaptured());
        assertEquals(0, metrics.getInsertEvents());
        assertEquals(0, metrics.getUpdateEvents());
        assertEquals(0, metrics.getDeleteEvents());
        assertEquals(0, metrics.getSchemaChangeEvents());
        assertEquals(0, metrics.getSnapshotRecords());
        assertEquals(0, metrics.getErrorsCount());
        assertEquals(0.0, metrics.getAverageEventLatencyMs());
        assertNull(metrics.getLastEventTime());
        assertNull(metrics.getLastCommitTime());
        assertNotNull(metrics.getStartTime());
        assertNull(metrics.getCurrentPosition());
    }

    @Test
    void testParameterizedConstructor() {
        Instant now = Instant.now();
        CDCMetrics customMetrics = new CDCMetrics(
                100, 20, 30, 40, 10, 500, 5, 15.5, now, now, now, "pos:123"
        );

        assertEquals(100, customMetrics.getTotalEventsCaptured());
        assertEquals(20, customMetrics.getInsertEvents());
        assertEquals(30, customMetrics.getUpdateEvents());
        assertEquals(40, customMetrics.getDeleteEvents());
        assertEquals(10, customMetrics.getSchemaChangeEvents());
        assertEquals(500, customMetrics.getSnapshotRecords());
        assertEquals(5, customMetrics.getErrorsCount());
        assertEquals(15.5, customMetrics.getAverageEventLatencyMs());
        assertEquals(now, customMetrics.getLastEventTime());
        assertEquals(now, customMetrics.getLastCommitTime());
        assertEquals(now, customMetrics.getStartTime());
        assertEquals("pos:123", customMetrics.getCurrentPosition());
    }

    @Test
    void testGetDataChangeEvents() {
        CDCMetrics customMetrics = new CDCMetrics(
                100, 20, 30, 40, 10, 0, 0, 0.0, null, null, Instant.now(), null
        );

        assertEquals(90, customMetrics.getDataChangeEvents()); // 20 + 30 + 40
    }

    @Test
    void testWithEventCounts() {
        CDCMetrics updated = metrics.withEventCounts(5, 10, 3, 2);

        assertEquals(20, updated.getTotalEventsCaptured()); // 5 + 10 + 3 + 2
        assertEquals(5, updated.getInsertEvents());
        assertEquals(10, updated.getUpdateEvents());
        assertEquals(3, updated.getDeleteEvents());
        assertEquals(2, updated.getSchemaChangeEvents());
        assertNotNull(updated.getLastEventTime());
    }

    @Test
    void testWithPosition() {
        String position = "binlog.000001:1234";
        CDCMetrics updated = metrics.withPosition(position);

        assertEquals(position, updated.getCurrentPosition());
        assertEquals(0, updated.getTotalEventsCaptured()); // Other fields unchanged
    }

    @Test
    void testWithCommit() {
        CDCMetrics updated = metrics.withCommit();

        assertNotNull(updated.getLastCommitTime());
        assertEquals(0, updated.getTotalEventsCaptured()); // Other fields unchanged
    }

    @Test
    void testWithError() {
        CDCMetrics updated = metrics.withError();

        assertEquals(1, updated.getErrorsCount());
        assertEquals(0, updated.getTotalEventsCaptured()); // Other fields unchanged

        CDCMetrics doubleError = updated.withError();
        assertEquals(2, doubleError.getErrorsCount());
    }

    @Test
    void testWithSnapshot() {
        long snapshotCount = 10000;
        CDCMetrics updated = metrics.withSnapshot(snapshotCount);

        assertEquals(snapshotCount, updated.getSnapshotRecords());
        assertEquals(0, updated.getTotalEventsCaptured()); // Other fields unchanged
    }

    @Test
    void testGetEventRateWithZeroDuration() {
        assertEquals(0.0, metrics.getEventRate());
    }

    @Test
    void testGetEventRateWithEvents() throws InterruptedException {
        // Sleep to ensure time difference
        Thread.sleep(10);

        CDCMetrics withEvents = metrics.withEventCounts(100, 0, 0, 0);
        double eventRate = withEvents.getEventRate();

        assertTrue(eventRate > 0);
    }

    @Test
    void testChaining() {
        CDCMetrics chained = metrics
                .withEventCounts(1, 2, 3, 4)
                .withPosition("pos:456")
                .withCommit()
                .withError()
                .withSnapshot(1000);

        assertEquals(10, chained.getTotalEventsCaptured()); // 1 + 2 + 3 + 4
        assertEquals(1, chained.getInsertEvents());
        assertEquals(2, chained.getUpdateEvents());
        assertEquals(3, chained.getDeleteEvents());
        assertEquals(4, chained.getSchemaChangeEvents());
        assertEquals("pos:456", chained.getCurrentPosition());
        assertNotNull(chained.getLastCommitTime());
        assertEquals(1, chained.getErrorsCount());
        assertEquals(1000, chained.getSnapshotRecords());
    }
}