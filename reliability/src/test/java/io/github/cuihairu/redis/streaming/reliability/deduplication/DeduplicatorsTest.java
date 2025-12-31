package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class DeduplicatorsTest {

    @Test
    void setDeduplicatorCheckAndMarkUsesSetAddReturnValue() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RSet set = mock(RSet.class);
        when(redisson.getSet("dedup")).thenReturn(set);

        when(set.add("k")).thenReturn(true).thenReturn(false);
        when(set.contains("k")).thenReturn(true);
        when(set.size()).thenReturn(1);

        SetDeduplicator<String> d = new SetDeduplicator<>(redisson, "dedup", s -> "k");

        assertFalse(d.checkAndMark("x"));
        assertTrue(d.checkAndMark("x"));
        assertTrue(d.isDuplicate("x"));
        assertEquals(1, d.getUniqueCount());
    }

    @Test
    void setDeduplicatorIgnoresNullElements() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RSet set = mock(RSet.class);
        when(redisson.getSet("dedup")).thenReturn(set);

        SetDeduplicator<String> d = new SetDeduplicator<>(redisson, "dedup", s -> s);
        assertFalse(d.isDuplicate(null));
        d.markAsSeen(null);
        assertFalse(d.checkAndMark(null));

        verify(set, never()).contains(any());
        verify(set, never()).add(any());
    }

    @Test
    void windowedDeduplicatorSetsAndRefreshesTtl() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RSet set = mock(RSet.class);
        when(redisson.getSet("win")).thenReturn(set);
        when(set.expire(any(Duration.class))).thenReturn(true);
        when(set.add("k")).thenReturn(true);
        when(set.remainTimeToLive()).thenReturn(123L);

        WindowedDeduplicator<String> d = new WindowedDeduplicator<>(redisson, "win", Duration.ofSeconds(5), s -> "k");
        assertEquals(Duration.ofSeconds(5), d.getWindowDuration());

        d.markAsSeen("x");
        assertEquals(123L, d.getRemainingTTL());

        verify(set, times(2)).expire(eq(Duration.ofSeconds(5))); // constructor + markAsSeen
    }

    @Test
    void bloomFilterDeduplicatorInitializesAndTracksUniqueCount() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RBloomFilter bloom = mock(RBloomFilter.class);
        when(redisson.getBloomFilter("bf")).thenReturn(bloom);

        when(bloom.isExists()).thenReturn(false);
        when(bloom.tryInit(anyLong(), anyDouble())).thenReturn(true);

        when(bloom.contains("k1")).thenReturn(false).thenReturn(true);
        when(bloom.add("k1")).thenReturn(true).thenReturn(false);
        when(bloom.getExpectedInsertions()).thenReturn(10L);
        when(bloom.getFalseProbability()).thenReturn(0.03);
        when(bloom.count()).thenReturn(7L);

        BloomFilterDeduplicator<String> d = new BloomFilterDeduplicator<>(redisson, "bf", 10, s -> "k1");
        verify(bloom).tryInit(eq(10L), eq(0.03));

        assertFalse(d.checkAndMark("x"));
        assertTrue(d.checkAndMark("x"));
        assertEquals(1, d.getUniqueCount());
        assertEquals(0.03, d.getExpectedFalseProbability(), 1e-9);
        assertEquals(7L, d.count());

        d.clear();
        verify(bloom).delete();
        assertEquals(0, d.getUniqueCount());
    }

    @Test
    void factoryCreatesExpectedImplementations() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RSet set = mock(RSet.class);
        when(redisson.getSet("name")).thenReturn(set);
        when(set.expire(any(Duration.class))).thenReturn(true);

        Deduplicator<String> w = DeduplicatorFactory.create(DeduplicatorFactory.DeduplicationStrategy.WINDOWED, redisson, "name", s -> s);
        assertInstanceOf(WindowedDeduplicator.class, w);

        Deduplicator<String> s = DeduplicatorFactory.create(DeduplicatorFactory.DeduplicationStrategy.SET, redisson, "name", x -> x);
        assertInstanceOf(SetDeduplicator.class, s);
    }

    @Test
    void bloomFilterConstructorValidatesArguments() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getBloomFilter("bf")).thenReturn(mock(RBloomFilter.class));

        assertThrows(IllegalArgumentException.class, () -> new BloomFilterDeduplicator<>(redisson, "bf", 0, (String x) -> x));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilterDeduplicator<>(redisson, "bf", 10, 0.0, (String x) -> x));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilterDeduplicator<>(redisson, "bf", 10, 1.0, (String x) -> x));
    }

    @Test
    void windowedDeduplicatorValidatesWindowDuration() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getSet("win")).thenReturn(mock(RSet.class));

        assertThrows(IllegalArgumentException.class, () -> new WindowedDeduplicator<>(redisson, "win", Duration.ZERO, (String x) -> x));
        assertThrows(IllegalArgumentException.class, () -> new WindowedDeduplicator<>(redisson, "win", Duration.ofMillis(-1), (String x) -> x));
    }

    @Test
    void defaultCheckAndMarkDelegatesToIsDuplicateThenMarkAsSeen() {
        class InMem implements Deduplicator<String> {
            private final java.util.Set<String> seen = new java.util.HashSet<>();
            @Override public boolean isDuplicate(String element) { return element != null && seen.contains(element); }
            @Override public void markAsSeen(String element) { if (element != null) seen.add(element); }
            @Override public void clear() { seen.clear(); }
            @Override public long getUniqueCount() { return seen.size(); }
        }

        Deduplicator<String> d = new InMem();
        assertFalse(d.checkAndMark("a"));
        assertTrue(d.checkAndMark("a"));
        assertEquals(1, d.getUniqueCount());
        assertNotNull(d);
    }
}
