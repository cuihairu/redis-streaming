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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
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
    void addReturnsFalseForInvalidInputs() {
        UVCounter counter = new UVCounter(mock(RedissonClient.class), "p", Duration.ofMinutes(3), Duration.ofMinutes(1));
        try {
            assertFalse(counter.add(" ", "u", Instant.now()));
            assertFalse(counter.add("home", " ", Instant.now()));
            assertFalse(counter.add(null, "u", Instant.now()));
            assertFalse(counter.add("home", null, Instant.now()));
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
    void countUsesBaseCountWhenSingleBucket() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> base = mock(RHyperLogLog.class);

        when(redisson.<String>getHyperLogLog("p:uv:home:60000")).thenReturn(base);
        when(base.count()).thenReturn(5L);

        // window < bucket => range stays within a single bucket
        UVCounter counter = new UVCounter(redisson, "p", Duration.ofSeconds(10), Duration.ofMinutes(1));
        try {
            long out = counter.count("home", Instant.ofEpochMilli(119_999));
            assertEquals(5L, out);
        } finally {
            counter.close();
        }
    }

    @Test
    void countReturnsZeroForInvalidRange() {
        UVCounter counter = new UVCounter(mock(RedissonClient.class), "p", Duration.ofMinutes(3), Duration.ofMinutes(1));
        try {
            assertEquals(0L, counter.count(" ", Instant.now(), Instant.now().plusSeconds(1)));
            assertEquals(0L, counter.count("home", null, Instant.now()));
            assertEquals(0L, counter.count("home", Instant.now(), null));
            assertEquals(0L, counter.count("home", Instant.ofEpochMilli(2), Instant.ofEpochMilli(1)));
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

    @Test
    void cleanupRemovesExpiredBucketsAndDropsPageWhenEmpty() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);

        when(redisson.<String>getSet("p:uv:pages")).thenReturn(pages);
        when(redisson.<String>getSet("p:uv:home:buckets")).thenReturn(bucketIndex);

        when(pages.readAll()).thenReturn(Set.of("home"));
        when(bucketIndex.readAll()).thenReturn(Set.of("p:uv:home:0", "p:uv:home:60000"));
        when(bucketIndex.size()).thenReturn(0);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(1), Duration.ofMinutes(1));
        try {
            var m = UVCounter.class.getDeclaredMethod("cleanupExpiredData");
            m.setAccessible(true);
            m.invoke(counter);
            verify(bucketIndex).remove("p:uv:home:0");
            verify(bucketIndex).remove("p:uv:home:60000");
            verify(pages).remove("home");
        } finally {
            counter.close();
        }
    }

    @Test
    void addWithNullTimestamp() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);

        when(redisson.<String>getHyperLogLog(any(String.class))).thenReturn(hll);
        when(redisson.<String>getSet("p:uv:pages")).thenReturn(pages);
        when(redisson.<String>getSet("p:uv:home:buckets")).thenReturn(bucketIndex);
        when(hll.add("u1")).thenReturn(true);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(10), Duration.ofMinutes(1));
        try {
            assertTrue(counter.add("home", "u1", null));
            verify(hll).add("u1");
        } finally {
            counter.close();
        }
    }

    @Test
    void addWithZeroTimestamp() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);

        when(redisson.<String>getHyperLogLog("p:uv:home:0")).thenReturn(hll);
        when(redisson.<String>getSet("p:uv:pages")).thenReturn(pages);
        when(redisson.<String>getSet("p:uv:home:buckets")).thenReturn(bucketIndex);
        when(hll.add("u1")).thenReturn(true);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(10), Duration.ofMinutes(1));
        try {
            assertTrue(counter.add("home", "u1", Instant.ofEpochMilli(0)));
            verify(hll).add("u1");
        } finally {
            counter.close();
        }
    }

    @Test
    void addWithMaxTimestamp() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);

        // Long.MAX_VALUE with 1ms bucket = Long.MAX_VALUE exactly
        when(redisson.<String>getHyperLogLog("p:uv:home:9223372036854775807")).thenReturn(hll);
        when(redisson.<String>getSet("p:uv:pages")).thenReturn(pages);
        when(redisson.<String>getSet("p:uv:home:buckets")).thenReturn(bucketIndex);
        when(hll.add("u1")).thenReturn(true);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(10), Duration.ofMillis(1));
        try {
            assertTrue(counter.add("home", "u1", Instant.ofEpochMilli(Long.MAX_VALUE)));
            verify(hll).add("u1");
        } finally {
            counter.close();
        }
    }

    @Test
    void addWithEmptyVisitorId() {
        UVCounter counter = new UVCounter(mock(RedissonClient.class), "p", Duration.ofMinutes(3), Duration.ofMinutes(1));
        try {
            assertFalse(counter.add("home", "", Instant.now()));
            assertFalse(counter.add("home", "  ", Instant.now()));
        } finally {
            counter.close();
        }
    }

    @Test
    void addWithNullVisitorId() {
        UVCounter counter = new UVCounter(mock(RedissonClient.class), "p", Duration.ofMinutes(3), Duration.ofMinutes(1));
        try {
            assertFalse(counter.add("home", null, Instant.now()));
        } finally {
            counter.close();
        }
    }

    @Test
    void addWithNewVisitorReturnsTrue() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);

        when(redisson.<String>getHyperLogLog(any(String.class))).thenReturn(hll);
        when(redisson.<String>getSet("p:uv:pages")).thenReturn(pages);
        when(redisson.<String>getSet("p:uv:home:buckets")).thenReturn(bucketIndex);
        when(hll.add("new_visitor")).thenReturn(true);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(10), Duration.ofMinutes(1));
        try {
            assertTrue(counter.add("home", "new_visitor", Instant.now()));
        } finally {
            counter.close();
        }
    }

    @Test
    void addWithExistingVisitorReturnsFalse() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);

        when(redisson.<String>getHyperLogLog(any(String.class))).thenReturn(hll);
        when(redisson.<String>getSet("p:uv:pages")).thenReturn(pages);
        when(redisson.<String>getSet("p:uv:home:buckets")).thenReturn(bucketIndex);
        when(hll.add("existing_visitor")).thenReturn(false);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(10), Duration.ofMinutes(1));
        try {
            assertFalse(counter.add("home", "existing_visitor", Instant.now()));
        } finally {
            counter.close();
        }
    }

    @Test
    void countWithSingleParameter() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> base = mock(RHyperLogLog.class);

        when(redisson.<String>getHyperLogLog("p:uv:home:0")).thenReturn(base);
        when(base.countWith(eq("p:uv:home:60000"), eq("p:uv:home:120000"), eq("p:uv:home:180000"))).thenReturn(5L);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(3), Duration.ofMinutes(1));
        try {
            long out = counter.count("home", Instant.ofEpochMilli(180_000));
            assertEquals(5L, out);
        } finally {
            counter.close();
        }
    }

    @Test
    void countWithRange() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> base = mock(RHyperLogLog.class);

        // Range 60_000 to 180_000 with 1min buckets => 60000, 120000, 180000 (inclusive)
        when(redisson.<String>getHyperLogLog("p:uv:home:60000")).thenReturn(base);
        when(base.countWith(eq("p:uv:home:120000"), eq("p:uv:home:180000"))).thenReturn(10L);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(3), Duration.ofMinutes(1));
        try {
            long out = counter.count("home", Instant.ofEpochMilli(60_000), Instant.ofEpochMilli(180_000));
            assertEquals(10L, out);
        } finally {
            counter.close();
        }
    }

    @Test
    void countWithInvalidPageReturnsZero() {
        UVCounter counter = new UVCounter(mock(RedissonClient.class), "p", Duration.ofMinutes(3), Duration.ofMinutes(1));
        try {
            assertEquals(0L, counter.count("", Instant.now(), Instant.now().plusSeconds(1)));
            assertEquals(0L, counter.count("  ", Instant.now(), Instant.now().plusSeconds(1)));
            assertEquals(0L, counter.count(null, Instant.now(), Instant.now().plusSeconds(1)));
        } finally {
            counter.close();
        }
    }

    @Test
    void resetWithNonExistentPage() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);

        when(redisson.<String>getSet("p:uv:nonexistent:buckets")).thenReturn(bucketIndex);
        when(redisson.<String>getSet("p:uv:pages")).thenReturn(pages);
        when(bucketIndex.readAll()).thenReturn(Set.of());
        when(redisson.getKeys()).thenReturn(mock(org.redisson.api.RKeys.class));

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(3), Duration.ofMinutes(1));
        try {
            counter.reset("nonexistent");
            verify(bucketIndex).clear();
        } finally {
            counter.close();
        }
    }

    @Test
    void resetWithNullPage() {
        UVCounter counter = new UVCounter(mock(RedissonClient.class), "p", Duration.ofMinutes(3), Duration.ofMinutes(1));
        try {
            counter.reset(null);
            // Should not throw exception
            assertTrue(true);
        } finally {
            counter.close();
        }
    }

    @Test
    void addWithSpecialCharactersInPageName() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);

        when(redisson.<String>getHyperLogLog(any(String.class))).thenReturn(hll);
        when(redisson.<String>getSet("p:uv:pages")).thenReturn(pages);
        when(redisson.<String>getSet("p:uv:page-with_special.chars:buckets")).thenReturn(bucketIndex);
        when(hll.add("u1")).thenReturn(true);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(10), Duration.ofMinutes(1));
        try {
            assertTrue(counter.add("page-with_special.chars", "u1", Instant.now()));
            verify(hll).add("u1");
        } finally {
            counter.close();
        }
    }

    @Test
    void addWithUnicodeInVisitorId() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);

        when(redisson.<String>getHyperLogLog(any(String.class))).thenReturn(hll);
        when(redisson.<String>getSet("p:uv:pages")).thenReturn(pages);
        when(redisson.<String>getSet("p:uv:home:buckets")).thenReturn(bucketIndex);
        when(hll.add("用户123")).thenReturn(true);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(10), Duration.ofMinutes(1));
        try {
            assertTrue(counter.add("home", "用户123", Instant.now()));
            verify(hll).add("用户123");
        } finally {
            counter.close();
        }
    }

    @Test
    void countWithEmptyWindow() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> base = mock(RHyperLogLog.class);

        when(redisson.<String>getHyperLogLog("p:uv:home:0")).thenReturn(base);
        when(base.count()).thenReturn(0L);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofSeconds(1), Duration.ofMinutes(1));
        try {
            long out = counter.count("home", Instant.ofEpochMilli(500), Instant.ofEpochMilli(500));
            assertEquals(0L, out);
        } finally {
            counter.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        UVCounter counter = new UVCounter(mock(RedissonClient.class), "p", Duration.ofMinutes(3), Duration.ofMinutes(1));
        counter.close();
        counter.close();
        assertTrue(true);
    }

    @Test
    void addWithSameVisitorMultipleTimes() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);

        when(redisson.<String>getHyperLogLog(any(String.class))).thenReturn(hll);
        when(redisson.<String>getSet("p:uv:pages")).thenReturn(pages);
        when(redisson.<String>getSet("p:uv:home:buckets")).thenReturn(bucketIndex);
        when(hll.add("u1")).thenReturn(false); // Already exists

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(10), Duration.ofMinutes(1));
        try {
            assertFalse(counter.add("home", "u1", Instant.now()));
            assertFalse(counter.add("home", "u1", Instant.now()));
            assertFalse(counter.add("home", "u1", Instant.now()));
        } finally {
            counter.close();
        }
    }

    @Test
    void countWithMultipleBuckets() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> base = mock(RHyperLogLog.class);

        // window=10min, bucket=1min, at 600_000 (10min)
        // end=600000, start=600000-600000=0
        // buckets: 0, 60000, 120000, ..., 600000 (11 buckets total)
        when(redisson.<String>getHyperLogLog(any(String.class))).thenReturn(base);
        when(base.countWith(
                eq("p:uv:home:60000"), eq("p:uv:home:120000"), eq("p:uv:home:180000"),
                eq("p:uv:home:240000"), eq("p:uv:home:300000"), eq("p:uv:home:360000"),
                eq("p:uv:home:420000"), eq("p:uv:home:480000"), eq("p:uv:home:540000"),
                eq("p:uv:home:600000")
        )).thenReturn(100L);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(10), Duration.ofMinutes(1));
        try {
            long out = counter.count("home", Instant.ofEpochMilli(600_000));
            assertEquals(100L, out);
        } finally {
            counter.close();
        }
    }
}
