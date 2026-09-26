package io.github.cuihairu.redis.streaming.aggregation;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * B-37 regression: the window key used to be built from the window class's simple
 * name only, so two windows of the same class with different sizes (1-minute and
 * 1-hour tumbling windows both start on the hour) mapped to one Redis key and
 * pruned/read each other's data. The key must carry the window size.
 *
 * <p>Uses only the public API, so it reproduces on the pre-fix code: both adds
 * landed on one key there.
 */
class WindowAggregatorKeyIsolationTest {

    @Test
    @SuppressWarnings("unchecked")
    void differentWindowSizesIsolateTheirKeys() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScoredSortedSet<Object> set = mock(RScoredSortedSet.class);
        when(redisson.<Object>getScoredSortedSet(anyString())).thenReturn(set);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");

        // the old layout collided exactly here: the minute window's add at :30 of the
        // hour has window start 3_600_000 — identical to the hour window's key
        Instant onTheHour = Instant.ofEpochMilli(3_600_000);
        aggregator.addValue(TumblingWindow.ofHours(1), "k", "v1", onTheHour.plusMillis(300_000));
        aggregator.addValue(TumblingWindow.ofMinutes(1), "k", "v2", onTheHour.plusMillis(30_000));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(redisson, times(2)).getScoredSortedSet(keys.capture());

        assertEquals(2, keys.getAllValues().size());
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1),
                "different-size windows of one class must not share a key (B-37)");
        // key layout: prefix:window:key:startMillis:WindowSimpleName:sizeMillis
        assertTrue(keys.getAllValues().get(0).endsWith(":3600000:TumblingWindow:3600000"),
                "hour window key carries start and size, got " + keys.getAllValues().get(0));
        assertTrue(keys.getAllValues().get(1).endsWith(":3600000:TumblingWindow:60000"),
                "minute window key carries start and size, got " + keys.getAllValues().get(1));
    }
}
