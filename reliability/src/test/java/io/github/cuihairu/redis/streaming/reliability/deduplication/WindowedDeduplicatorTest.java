package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.junit.jupiter.api.Test;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WindowedDeduplicatorTest {

    @Test
    void constructorSetsTtlAndRefreshesOnWrites() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RSet<String> set = mock(RSet.class);
        when(redisson.<String>getSet("s")).thenReturn(set);

        WindowedDeduplicator<String> dedup = new WindowedDeduplicator<>(redisson, "s", Duration.ofSeconds(5), (String v) -> v);

        verify(set).expire(Duration.ofSeconds(5));

        dedup.markAsSeen("k1");
        dedup.checkAndMark("k2");

        verify(set, times(3)).expire(Duration.ofSeconds(5));
    }

    @Test
    void getRemainingTtlDelegatesToRedisSet() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RSet<String> set = mock(RSet.class);
        when(redisson.<String>getSet("s")).thenReturn(set);
        when(set.remainTimeToLive()).thenReturn(123L);

        WindowedDeduplicator<String> dedup = new WindowedDeduplicator<>(redisson, "s", Duration.ofSeconds(5), (String v) -> v);
        assertEquals(123L, dedup.getRemainingTTL());
    }

    @Test
    void windowDurationMustBePositive() {
        RedissonClient redisson = mock(RedissonClient.class);
        assertThrows(IllegalArgumentException.class,
                () -> new WindowedDeduplicator<>(redisson, "s", Duration.ZERO, (String v) -> v));
    }
}
