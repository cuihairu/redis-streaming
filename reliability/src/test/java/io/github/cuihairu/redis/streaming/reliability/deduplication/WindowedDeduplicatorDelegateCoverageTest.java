package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.junit.jupiter.api.Test;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link WindowedDeduplicator} delegate methods that were previously
 * never invoked by tests: {@code isDuplicate}, {@code clear} and
 * {@code getUniqueCount}.
 */
class WindowedDeduplicatorDelegateCoverageTest {

    @SuppressWarnings("unchecked")
    @Test
    void isDuplicateClearAndCountDelegateToTheBackingSet() {
        RedissonClient redisson = mock(RedissonClient.class);
        RSet<String> set = mock(RSet.class);
        when(redisson.<String>getSet(anyString())).thenReturn(set);
        when(set.contains("k1")).thenReturn(true);
        when(set.size()).thenReturn(7);

        WindowedDeduplicator<String> dedup =
                new WindowedDeduplicator<>(redisson, "win-dedup", Duration.ofMinutes(5), s -> s);

        assertTrue(dedup.isDuplicate("k1"), "seen keys must be reported as duplicates");
        assertFalse(dedup.isDuplicate("k2"), "unknown keys must not be reported as duplicates");

        assertEquals(7, dedup.getUniqueCount(), "the unique count must mirror the backing set size");

        dedup.clear();
        verify(set).clear();
    }
}
