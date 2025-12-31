package io.github.cuihairu.redis.streaming.api.watermark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WatermarkTest {

    @Test
    void compareToOrdersByTimestamp() {
        Watermark w1 = new Watermark(10);
        Watermark w2 = new Watermark(20);

        assertTrue(w1.compareTo(w2) < 0);
        assertTrue(w2.compareTo(w1) > 0);
        assertEquals(0, w1.compareTo(new Watermark(10)));
    }

    @Test
    void equalsAndHashCodeUseTimestamp() {
        Watermark w1a = new Watermark(123);
        Watermark w1b = new Watermark(123);
        Watermark w2 = new Watermark(124);

        assertEquals(w1a, w1b);
        assertEquals(w1a.hashCode(), w1b.hashCode());
        assertNotEquals(w1a, w2);
        assertNotEquals(w1a, null);
        assertNotEquals(w1a, "not-a-watermark");
    }

    @Test
    void maxWatermarkIsLongMax() {
        Watermark max = Watermark.maxWatermark();
        assertEquals(Long.MAX_VALUE, max.getTimestamp());
        assertEquals(max, new Watermark(Long.MAX_VALUE));
    }

    @Test
    void testGetTimestamp() {
        Watermark w = new Watermark(12345L);
        assertEquals(12345L, w.getTimestamp());
    }

    @Test
    void testConstructorWithZeroTimestamp() {
        Watermark w = new Watermark(0);
        assertEquals(0, w.getTimestamp());
    }

    @Test
    void testConstructorWithNegativeTimestamp() {
        Watermark w = new Watermark(-1000);
        assertEquals(-1000, w.getTimestamp());
    }

    @Test
    void testConstructorWithLongMaxTimestamp() {
        Watermark w = new Watermark(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, w.getTimestamp());
    }

    @Test
    void testConstructorWithLongMinTimestamp() {
        Watermark w = new Watermark(Long.MIN_VALUE);
        assertEquals(Long.MIN_VALUE, w.getTimestamp());
    }

    @Test
    void testCompareToWithEqualTimestamps() {
        Watermark w1 = new Watermark(100);
        Watermark w2 = new Watermark(100);

        assertEquals(0, w1.compareTo(w2));
        assertEquals(0, w2.compareTo(w1));
    }

    @Test
    void testCompareToWithLargeDifference() {
        Watermark w1 = new Watermark(0);
        Watermark w2 = new Watermark(Long.MAX_VALUE);

        assertTrue(w1.compareTo(w2) < 0);
        assertTrue(w2.compareTo(w1) > 0);
    }

    @Test
    void testCompareToWithNegativeTimestamps() {
        Watermark w1 = new Watermark(-100);
        Watermark w2 = new Watermark(-50);

        assertTrue(w1.compareTo(w2) < 0);
        assertTrue(w2.compareTo(w1) > 0);
    }

    @Test
    void testCompareToWithMixedSigns() {
        Watermark w1 = new Watermark(-100);
        Watermark w2 = new Watermark(100);

        assertTrue(w1.compareTo(w2) < 0);
        assertTrue(w2.compareTo(w1) > 0);
    }

    @Test
    void testEqualsWithSameObject() {
        Watermark w = new Watermark(100);
        assertEquals(w, w);
    }

    @Test
    void testEqualsWithNull() {
        Watermark w = new Watermark(100);
        assertNotEquals(w, null);
    }

    @Test
    void testEqualsWithDifferentClass() {
        Watermark w = new Watermark(100);
        assertNotEquals(w, "not-a-watermark");
        assertNotEquals(w, 100);
    }

    @Test
    void testEqualsWithDifferentTimestamps() {
        Watermark w1 = new Watermark(100);
        Watermark w2 = new Watermark(200);
        assertNotEquals(w1, w2);
    }

    @Test
    void testHashCodeConsistency() {
        Watermark w = new Watermark(12345);

        // Multiple calls should return same hashCode
        int hash1 = w.hashCode();
        int hash2 = w.hashCode();
        assertEquals(hash1, hash2);

        // Equal objects should have equal hashCodes
        Watermark w2 = new Watermark(12345);
        assertEquals(w.hashCode(), w2.hashCode());
    }

    @Test
    void testHashCodeWithZero() {
        Watermark w = new Watermark(0);
        assertEquals(Long.hashCode(0), w.hashCode());
    }

    @Test
    void testHashCodeWithMaxValue() {
        Watermark w = new Watermark(Long.MAX_VALUE);
        assertEquals(Long.hashCode(Long.MAX_VALUE), w.hashCode());
    }

    @Test
    void testToString() {
        Watermark w = new Watermark(12345);
        String str = w.toString();

        assertTrue(str.contains("12345"));
        assertTrue(str.contains("Watermark"));
    }

    @Test
    void testToStringWithZero() {
        Watermark w = new Watermark(0);
        String str = w.toString();

        assertTrue(str.contains("0"));
    }

    @Test
    void testToStringWithNegativeTimestamp() {
        Watermark w = new Watermark(-1000);
        String str = w.toString();

        assertTrue(str.contains("-1000"));
    }

    @Test
    void testToStringWithMaxTimestamp() {
        Watermark w = new Watermark(Long.MAX_VALUE);
        String str = w.toString();

        assertTrue(str.contains(String.valueOf(Long.MAX_VALUE)));
    }

    @Test
    void testMaxWatermarkIsSingleton() {
        Watermark max1 = Watermark.maxWatermark();
        Watermark max2 = Watermark.maxWatermark();

        // Each call creates a new instance, but they should be equal
        assertEquals(max1, max2);
        assertEquals(max1.hashCode(), max2.hashCode());
    }

    @Test
    void testMaxWatermarkCompareTo() {
        Watermark max = Watermark.maxWatermark();
        Watermark w = new Watermark(Long.MAX_VALUE - 1);

        assertTrue(max.compareTo(w) > 0);
        assertTrue(w.compareTo(max) < 0);
    }

    @Test
    void testWatermarkIsSerializable() {
        Watermark w = new Watermark(12345);
        assertTrue(w instanceof java.io.Serializable);
    }

    @Test
    void testComparableInterface() {
        Watermark w = new Watermark(100);
        assertTrue(w instanceof Comparable);
    }

    @Test
    void testCompareToForSorting() {
        Watermark[] watermarks = {
            new Watermark(50),
            new Watermark(10),
            new Watermark(100),
            new Watermark(30)
        };

        java.util.Arrays.sort(watermarks);

        assertEquals(10, watermarks[0].getTimestamp());
        assertEquals(30, watermarks[1].getTimestamp());
        assertEquals(50, watermarks[2].getTimestamp());
        assertEquals(100, watermarks[3].getTimestamp());
    }
}

