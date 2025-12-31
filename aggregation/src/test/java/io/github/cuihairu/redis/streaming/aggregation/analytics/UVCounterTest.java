package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RHyperLogLog;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UVCounterTest {

    @Test
    void addStoresVisitorInBucketAndIndexesPage() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);

        Instant ts = Instant.ofEpochMilli(120_000); // 2 min
        String bucketKey = "p:uv:home:120000";

        when(redisson.<String>getHyperLogLog(bucketKey)).thenReturn(hll);
        when(redisson.<String>getSet("p:uv:pages")).thenReturn(pages);
        when(redisson.<String>getSet("p:uv:home:buckets")).thenReturn(bucketIndex);
        when(hll.add("u1")).thenReturn(true);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(10), Duration.ofMinutes(1));
        try {
            assertTrue(counter.add("home", "u1", ts));
            verify(hll).add("u1");
            verify(hll).expire(any(Duration.class));
            verify(pages).add("home");
            verify(bucketIndex).add(bucketKey);
        } finally {
            counter.close();
        }
    }

    @Test
    void countUsesCountWithAcrossBuckets() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> base = mock(RHyperLogLog.class);

        // window=3min, bucket=1min => keys: 0, 60000, 120000, 180000 (inclusive)
        when(redisson.<String>getHyperLogLog("p:uv:home:0")).thenReturn(base);
        when(base.countWith(eq("p:uv:home:60000"), eq("p:uv:home:120000"), eq("p:uv:home:180000"))).thenReturn(7L);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(3), Duration.ofMinutes(1));
        try {
            long out = counter.count("home", Instant.ofEpochMilli(180_000));
            assertEquals(7L, out);
        } finally {
            counter.close();
        }
    }

    @Test
    void resetDeletesAllKnownBucketsBestEffort() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);

        when(redisson.<String>getSet("p:uv:home:buckets")).thenReturn(bucketIndex);
        when(redisson.<String>getSet("p:uv:pages")).thenReturn(pages);
        when(bucketIndex.readAll()).thenReturn(Set.of("p:uv:home:0", "p:uv:home:60000"));
        when(redisson.getKeys()).thenReturn(mock(org.redisson.api.RKeys.class));

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(3), Duration.ofMinutes(1));
        try {
            counter.reset("home");
            verify(bucketIndex).clear();
            verify(pages).remove("home");
        } finally {
            counter.close();
        }
    }
}
