package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Residual branches in the in-memory runtime helpers and {@link NumberAggregationUtils}:
 * the package-private {@code InMemoryDataStream.fromRecords(Supplier, WatermarkState)} overload,
 * {@code WindowKey.equals} for a differing window start and the floating-point arms of
 * {@code NumberAggregationUtils.add}.
 */
class InMemoryHelpersGapClosureTest {

    @Test
    void fromRecordsWithExplicitWatermarkStateIsUsable() {
        WatermarkState state = new WatermarkState();
        InMemoryDataStream<String> stream = InMemoryDataStream.fromRecords(
                () -> List.<InMemoryRecord<String>>of(new InMemoryRecord<>("a", 10L)).iterator(), state);
        assertNotNull(stream);
        assertTrue(stream.iterator().hasNext(), "records flow through the two-arg overload");
    }

    @Test
    void windowKeyEqualsDetectsDifferentWindowStart() throws Exception {
        Class<?> windowKeyClass = Class.forName(
                "io.github.cuihairu.redis.streaming.runtime.internal.InMemoryWindowedStream$WindowKey");
        Method of = windowKeyClass.getDeclaredMethod("of", Object.class, WindowAssigner.Window.class);
        of.setAccessible(true);
        Method equals = windowKeyClass.getDeclaredMethod("equals", Object.class);
        equals.setAccessible(true);

        Object a = of.invoke(null, "k", new SimpleWindow(0, 10));
        Object differentStart = of.invoke(null, "k", new SimpleWindow(1, 10));
        assertEquals(Boolean.FALSE, equals.invoke(a, differentStart));
    }

    @Test
    void numberAddUsesDoubleArithmeticForAnyFloatOrDoubleOperand() {
        assertEquals(3.5d, ((Number) NumberAggregationUtils.add(1.5f, 2)).doubleValue(), 1e-9);
        assertEquals(3.5d, ((Number) NumberAggregationUtils.add(1, 2.5f)).doubleValue(), 1e-9);
        assertEquals(3.0d, ((Number) NumberAggregationUtils.add(1.0d, 2L)).doubleValue(), 1e-9);
        assertEquals(3.0d, ((Number) NumberAggregationUtils.add(1L, 2.0d)).doubleValue(), 1e-9);
        assertEquals(3L, NumberAggregationUtils.add(1L, 2L));
        assertEquals(0L, NumberAggregationUtils.add(null, null));
        assertEquals(2L, NumberAggregationUtils.add(null, 2L));
        assertEquals(1L, NumberAggregationUtils.add(1L, null));
    }

    private static final class SimpleWindow implements WindowAssigner.Window {
        private final long start;
        private final long end;

        SimpleWindow(long start, long end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public long getStart() {
            return start;
        }

        @Override
        public long getEnd() {
            return end;
        }
    }
}
