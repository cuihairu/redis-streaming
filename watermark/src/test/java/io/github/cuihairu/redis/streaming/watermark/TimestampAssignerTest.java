package io.github.cuihairu.redis.streaming.watermark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WatermarkStrategy.TimestampAssigner interface
 */
class TimestampAssignerTest {

    // ===== Basic Functionality Tests =====

    @Test
    void testTimestampAssignerWithLambda() {
        WatermarkStrategy.TimestampAssigner<TestEvent> assigner =
            (event, recordTimestamp) -> event.timestamp;

        TestEvent event = new TestEvent(12345, "test-data");
        long extracted = assigner.extractTimestamp(event, 9999);

        assertEquals(12345, extracted);
    }

    @Test
    void testTimestampAssignerUsesRecordTimestamp() {
        WatermarkStrategy.TimestampAssigner<TestEvent> assigner =
            (event, recordTimestamp) -> recordTimestamp;

        TestEvent event = new TestEvent(12345, "test-data");
        long extracted = assigner.extractTimestamp(event, 9999);

        assertEquals(9999, extracted);
    }

    @Test
    void testTimestampAssignerCombinesFields() {
        WatermarkStrategy.TimestampAssigner<TestEvent> assigner =
            (event, recordTimestamp) -> event.timestamp + 1000;

        TestEvent event = new TestEvent(5000, "test-data");
        long extracted = assigner.extractTimestamp(event, 0);

        assertEquals(6000, extracted);
    }

    @Test
    void testTimestampAssignerWithZeroTimestamp() {
        WatermarkStrategy.TimestampAssigner<TestEvent> assigner =
            (event, recordTimestamp) -> event.timestamp;

        TestEvent event = new TestEvent(0, "zero-timestamp");
        long extracted = assigner.extractTimestamp(event, 9999);

        assertEquals(0, extracted);
    }

    @Test
    void testTimestampAssignerWithNegativeTimestamp() {
        WatermarkStrategy.TimestampAssigner<TestEvent> assigner =
            (event, recordTimestamp) -> event.timestamp;

        TestEvent event = new TestEvent(-1000, "negative-timestamp");
        long extracted = assigner.extractTimestamp(event, 9999);

        assertEquals(-1000, extracted);
    }

    @Test
    void testTimestampAssignerWithMaxLong() {
        WatermarkStrategy.TimestampAssigner<TestEvent> assigner =
            (event, recordTimestamp) -> event.timestamp;

        TestEvent event = new TestEvent(Long.MAX_VALUE, "max-timestamp");
        long extracted = assigner.extractTimestamp(event, 0);

        assertEquals(Long.MAX_VALUE, extracted);
    }

    @Test
    void testTimestampAssignerWithNullEvent() {
        WatermarkStrategy.TimestampAssigner<TestEvent> assigner =
            (event, recordTimestamp) -> event == null ? recordTimestamp : event.timestamp;

        long extracted = assigner.extractTimestamp(null, 5555);

        assertEquals(5555, extracted);
    }

    @Test
    void testTimestampAssignerWithMultipleFields() {
        WatermarkStrategy.TimestampAssigner<ComplexEvent> assigner =
            (event, recordTimestamp) -> event.epochMillis + event.nanoOffset / 1000000;

        ComplexEvent event = new ComplexEvent(1000, 500000);
        long extracted = assigner.extractTimestamp(event, 0);

        assertEquals(1000, extracted);
    }

    @Test
    void testTimestampAssignerWithConditionalLogic() {
        WatermarkStrategy.TimestampAssigner<TestEvent> assigner =
            (event, recordTimestamp) -> event.timestamp > 0 ? event.timestamp : recordTimestamp;

        TestEvent event1 = new TestEvent(1000, "positive");
        assertEquals(1000, assigner.extractTimestamp(event1, 9999));

        TestEvent event2 = new TestEvent(-1, "negative");
        assertEquals(9999, assigner.extractTimestamp(event2, 9999));
    }

    @Test
    void testTimestampAssignerIgnoresRecordTimestamp() {
        WatermarkStrategy.TimestampAssigner<TestEvent> assigner =
            (event, recordTimestamp) -> {
                // Always use event timestamp, ignore record timestamp
                return event.timestamp;
            };

        TestEvent event = new TestEvent(12345, "test-data");
        assertEquals(12345, assigner.extractTimestamp(event, 9999));
        assertEquals(12345, assigner.extractTimestamp(event, 0));
        assertEquals(12345, assigner.extractTimestamp(event, Long.MAX_VALUE));
    }

    @Test
    void testTimestampAssignerWithMathOperation() {
        WatermarkStrategy.TimestampAssigner<TestEvent> assigner =
            (event, recordTimestamp) -> event.timestamp * 2;

        TestEvent event = new TestEvent(1000, "test-data");
        long extracted = assigner.extractTimestamp(event, 0);

        assertEquals(2000, extracted);
    }

    @Test
    void testTimestampAssignerWithModulo() {
        WatermarkStrategy.TimestampAssigner<TestEvent> assigner =
            (event, recordTimestamp) -> event.timestamp % 1000;

        TestEvent event = new TestEvent(12345, "test-data");
        long extracted = assigner.extractTimestamp(event, 0);

        assertEquals(345, extracted);
    }

    // ===== Test Event Classes =====

    static class TestEvent {
        final long timestamp;
        final String data;

        TestEvent(long timestamp, String data) {
            this.timestamp = timestamp;
            this.data = data;
        }
    }

    static class ComplexEvent {
        final long epochMillis;
        final int nanoOffset;

        ComplexEvent(long epochMillis, int nanoOffset) {
            this.epochMillis = epochMillis;
            this.nanoOffset = nanoOffset;
        }
    }
}
