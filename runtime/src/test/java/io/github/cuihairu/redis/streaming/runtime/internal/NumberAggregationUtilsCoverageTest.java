package io.github.cuihairu.redis.streaming.runtime.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers all branches of {@link NumberAggregationUtils}, including the serialized
 * class-name based casts that windowed aggregations use after state restore.
 */
class NumberAggregationUtilsCoverageTest {

    @Test
    void castToSameTypeWithNumberSampleCoversAllNumericKinds() {
        assertEquals(3, NumberAggregationUtils.castToSameType(3L, (Number) Integer.valueOf(7)));
        assertEquals(3L, NumberAggregationUtils.castToSameType(3, (Number) Long.valueOf(7L)));
        assertEquals(3.0d, NumberAggregationUtils.castToSameType(3, (Number) Double.valueOf(7.0d)));
        assertEquals(3.0f, NumberAggregationUtils.castToSameType(3, (Number) Float.valueOf(7.0f)));
        assertEquals((short) 3, NumberAggregationUtils.castToSameType(3, (Number) Short.valueOf((short) 7)));
        assertEquals((byte) 3, NumberAggregationUtils.castToSameType(3, (Number) Byte.valueOf((byte) 7)));
        Number untouched = NumberAggregationUtils.castToSameType(3, (Number) new java.math.BigDecimal("7"));
        assertSame(3, untouched);
    }

    @Test
    void castToSameTypeWithNullSampleKeepsValue() {
        assertSame(3, NumberAggregationUtils.castToSameType(3, (Number) null));
    }

    @Test
    void castToSameTypeWithClassNameCoversAllBranches() {
        assertEquals(3, NumberAggregationUtils.castToSameType(3L, "java.lang.Integer"));
        assertEquals(3L, NumberAggregationUtils.castToSameType(3, "java.lang.Long"));
        assertEquals(3.0d, NumberAggregationUtils.castToSameType(3, "java.lang.Double"));
        assertEquals(3.0f, NumberAggregationUtils.castToSameType(3, "java.lang.Float"));
        assertEquals((short) 3, NumberAggregationUtils.castToSameType(3, "java.lang.Short"));
        assertEquals((byte) 3, NumberAggregationUtils.castToSameType(3, "java.lang.Byte"));
        assertSame(3, NumberAggregationUtils.castToSameType(3, "java.math.BigDecimal"));
        assertSame(3, NumberAggregationUtils.castToSameType(3, (String) null));
        assertSame(3, NumberAggregationUtils.castToSameType(3, "  "));
    }

    @Test
    void addCoversNullAndPromotionBranches() {
        assertEquals(0L, NumberAggregationUtils.add(null, null));
        assertEquals(5, NumberAggregationUtils.add(null, 5));
        assertEquals(5, NumberAggregationUtils.add(5, null));
        assertEquals(7L, NumberAggregationUtils.add(3, 4));
        assertEquals(7.5d, NumberAggregationUtils.add(3, 4.5d));
        assertEquals(7.5d, NumberAggregationUtils.add(3.5d, 4));
    }
}
