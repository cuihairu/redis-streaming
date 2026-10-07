package io.github.cuihairu.redis.streaming.runtime.internal;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Numeric helpers shared by the runtime engines for built-in aggregations (sum/count).
 *
 * <p>Integeral values accumulate as {@code long}; any floating-point operand promotes the
 * result to {@code double}. Results can be cast back to the type of a sample value so that
 * keyed/windowed streams keep their original numeric type.</p>
 */
public final class NumberAggregationUtils {

    private NumberAggregationUtils() {
    }

    /**
     * Add two numbers with floating-point promotion when either operand is fractional.
     *
     * <p>Integral overflow fails loudly with {@link ArithmeticException} (RT-L8) instead
     * of silently wrapping, and {@link BigInteger}/{@link BigDecimal} operands are added
     * exactly instead of being truncated to {@code long}.</p>
     *
     * @param a the running total (may be {@code null})
     * @param b the value to add (may be {@code null})
     * @return the sum; {@code 0L} when both operands are {@code null}
     */
    public static Number add(Number a, Number b) {
        if (b == null) {
            return a == null ? 0L : a;
        }
        if (a == null) {
            return b;
        }
        if (a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float) {
            return a.doubleValue() + b.doubleValue();
        }
        if (a instanceof BigDecimal || b instanceof BigDecimal) {
            return toBigDecimal(a).add(toBigDecimal(b));
        }
        if (a instanceof BigInteger || b instanceof BigInteger) {
            return toBigInteger(a).add(toBigInteger(b));
        }
        return Math.addExact(a.longValue(), b.longValue());
    }

    private static BigDecimal toBigDecimal(Number n) {
        return n instanceof BigDecimal bd ? bd : new BigDecimal(n.toString());
    }

    private static BigInteger toBigInteger(Number n) {
        return n instanceof BigInteger bi ? bi : new BigInteger(n.toString());
    }

    /**
     * Cast an aggregated number back to the type of a sample value.
     *
     * @param value  the value to cast (must not be {@code null})
     * @param sample the sample whose type is applied; {@code null} keeps the value unchanged
     * @return the cast value
     */
    public static Number castToSameType(Number value, Number sample) {
        if (sample == null) {
            return value;
        }
        if (sample instanceof Integer) return value.intValue();
        if (sample instanceof Long) return value.longValue();
        if (sample instanceof Double) return value.doubleValue();
        if (sample instanceof Float) return value.floatValue();
        if (sample instanceof Short) return value.shortValue();
        if (sample instanceof Byte) return value.byteValue();
        return value;
    }

    /**
     * Cast an aggregated number back to the type identified by a class-name string
     * (as stored in serialized state). Unknown or missing names keep the value unchanged.
     *
     * @param value            the value to cast (must not be {@code null})
     * @param sampleClassName  fully-qualified numeric type name, may be {@code null}
     * @return the cast value
     */
    public static Number castToSameType(Number value, String sampleClassName) {
        if (sampleClassName == null || sampleClassName.isBlank()) {
            return value;
        }
        return switch (sampleClassName) {
            case "java.lang.Integer" -> value.intValue();
            case "java.lang.Long" -> value.longValue();
            case "java.lang.Double" -> value.doubleValue();
            case "java.lang.Float" -> value.floatValue();
            case "java.lang.Short" -> value.shortValue();
            case "java.lang.Byte" -> value.byteValue();
            default -> value;
        };
    }
}
