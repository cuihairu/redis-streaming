package io.github.cuihairu.redis.streaming.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricType enum
 */
class MetricTypeTest {

    // ===== Enum Values Tests =====

    @Test
    void testCounterEnumValueExists() {
        assertEquals(MetricType.COUNTER, MetricType.valueOf("COUNTER"));
    }

    @Test
    void testGaugeEnumValueExists() {
        assertEquals(MetricType.GAUGE, MetricType.valueOf("GAUGE"));
    }

    @Test
    void testHistogramEnumValueExists() {
        assertEquals(MetricType.HISTOGRAM, MetricType.valueOf("HISTOGRAM"));
    }

    @Test
    void testMeterEnumValueExists() {
        assertEquals(MetricType.METER, MetricType.valueOf("METER"));
    }

    @Test
    void testTimerEnumValueExists() {
        assertEquals(MetricType.TIMER, MetricType.valueOf("TIMER"));
    }

    // ===== Enum Constants Tests =====

    @Test
    void testAllEnumValues() {
        MetricType[] values = MetricType.values();

        assertEquals(5, values.length);
        assertEquals(MetricType.COUNTER, values[0]);
        assertEquals(MetricType.GAUGE, values[1]);
        assertEquals(MetricType.HISTOGRAM, values[2]);
        assertEquals(MetricType.METER, values[3]);
        assertEquals(MetricType.TIMER, values[4]);
    }

    // ===== Enum Serialization Tests =====

    @Test
    void testEnumImplementsSerializable() {
        // MetricType implements Serializable, so it should be serializable
        assertTrue(MetricType.COUNTER instanceof java.io.Serializable);
        assertTrue(MetricType.GAUGE instanceof java.io.Serializable);
        assertTrue(MetricType.HISTOGRAM instanceof java.io.Serializable);
        assertTrue(MetricType.METER instanceof java.io.Serializable);
        assertTrue(MetricType.TIMER instanceof java.io.Serializable);
    }

    // ===== valueOf Tests =====

    @Test
    void testValueOfWithInvalidName() {
        assertThrows(IllegalArgumentException.class, () -> MetricType.valueOf("INVALID"));
    }

    @Test
    void testValueOfCaseSensitive() {
        assertEquals(MetricType.COUNTER, MetricType.valueOf("COUNTER"));
        assertThrows(IllegalArgumentException.class, () -> MetricType.valueOf("counter"));
        assertThrows(IllegalArgumentException.class, () -> MetricType.valueOf("Counter"));
    }

    // ===== name Tests =====

    @Test
    void testCounterName() {
        assertEquals("COUNTER", MetricType.COUNTER.name());
    }

    @Test
    void testGaugeName() {
        assertEquals("GAUGE", MetricType.GAUGE.name());
    }

    @Test
    void testHistogramName() {
        assertEquals("HISTOGRAM", MetricType.HISTOGRAM.name());
    }

    @Test
    void testMeterName() {
        assertEquals("METER", MetricType.METER.name());
    }

    @Test
    void testTimerName() {
        assertEquals("TIMER", MetricType.TIMER.name());
    }

    // ===== ordinal Tests =====

    @Test
    void testCounterOrdinal() {
        assertEquals(0, MetricType.COUNTER.ordinal());
    }

    @Test
    void testGaugeOrdinal() {
        assertEquals(1, MetricType.GAUGE.ordinal());
    }

    @Test
    void testHistogramOrdinal() {
        assertEquals(2, MetricType.HISTOGRAM.ordinal());
    }

    @Test
    void testMeterOrdinal() {
        assertEquals(3, MetricType.METER.ordinal());
    }

    @Test
    void testTimerOrdinal() {
        assertEquals(4, MetricType.TIMER.ordinal());
    }

    // ===== Enum Equality Tests =====

    @Test
    void testEnumEquality() {
        assertEquals(MetricType.COUNTER, MetricType.COUNTER);
        assertEquals(MetricType.GAUGE, MetricType.GAUGE);
        assertEquals(MetricType.HISTOGRAM, MetricType.HISTOGRAM);
        assertEquals(MetricType.METER, MetricType.METER);
        assertEquals(MetricType.TIMER, MetricType.TIMER);
    }

    @Test
    void testEnumInequality() {
        assertNotEquals(MetricType.COUNTER, MetricType.GAUGE);
        assertNotEquals(MetricType.GAUGE, MetricType.HISTOGRAM);
        assertNotEquals(MetricType.HISTOGRAM, MetricType.METER);
        assertNotEquals(MetricType.METER, MetricType.TIMER);
        assertNotEquals(MetricType.TIMER, MetricType.COUNTER);
    }

    // ===== Semantic Meaning Tests =====

    @Test
    void testCounterSemantics() {
        // Counter metric - monotonically increasing value
        MetricType type = MetricType.COUNTER;
        assertEquals("COUNTER", type.name());
    }

    @Test
    void testGaugeSemantics() {
        // Gauge metric - arbitrary value that can go up or down
        MetricType type = MetricType.GAUGE;
        assertEquals("GAUGE", type.name());
    }

    @Test
    void testHistogramSemantics() {
        // Histogram metric - distribution of values
        MetricType type = MetricType.HISTOGRAM;
        assertEquals("HISTOGRAM", type.name());
    }

    @Test
    void testMeterSemantics() {
        // Meter metric - rate of events over time
        MetricType type = MetricType.METER;
        assertEquals("METER", type.name());
    }

    @Test
    void testTimerSemantics() {
        // Timer metric - duration and rate of events
        MetricType type = MetricType.TIMER;
        assertEquals("TIMER", type.name());
    }
}
