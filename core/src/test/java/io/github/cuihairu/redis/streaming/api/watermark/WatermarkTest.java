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
}

