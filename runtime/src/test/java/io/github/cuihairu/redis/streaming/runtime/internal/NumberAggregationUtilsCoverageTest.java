package io.github.cuihairu.redis.streaming.runtime.internal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void integralOverflowFailsLoudlyInsteadOfSilentlyWrapping() {
        // RT-L8: the old long addition wrapped to a negative sum without any signal
        assertThrows(ArithmeticException.class,
                () -> NumberAggregationUtils.add(Long.MAX_VALUE, 1L));
        assertThrows(ArithmeticException.class,
                () -> NumberAggregationUtils.add(Long.MIN_VALUE, -1L));
    }

    @Test
    void bigIntegerAndBigDecimalAreAddedExactly() {
        // RT-L8: the old longValue() truncation lost everything above Long.MAX_VALUE
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                NumberAggregationUtils.add(BigInteger.valueOf(Long.MAX_VALUE), 1));
        assertEquals(new BigDecimal("0.3"),
                NumberAggregationUtils.add(new BigDecimal("0.1"), new BigDecimal("0.2")));
        assertEquals(0L, NumberAggregationUtils.add(null, null));
        assertEquals(new BigDecimal("1.5"),
                NumberAggregationUtils.add(new BigDecimal("0.5"), 1));
    }
}
