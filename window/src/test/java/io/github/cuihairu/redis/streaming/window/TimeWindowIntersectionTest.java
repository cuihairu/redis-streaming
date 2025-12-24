package io.github.cuihairu.redis.streaming.window;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeWindowIntersectionTest {

    @Test
    void intersectsIsFalseForAdjacentAndSeparateWindows() {
        TimeWindow w1 = new TimeWindow(0, 10);
        TimeWindow adjacent = new TimeWindow(10, 20);
        TimeWindow separate = new TimeWindow(20, 30);
        TimeWindow overlapping = new TimeWindow(9, 20);

        assertFalse(TimeWindow.intersects(w1, adjacent));
        assertFalse(TimeWindow.intersects(w1, separate));
        assertTrue(TimeWindow.intersects(w1, overlapping));
    }
}

