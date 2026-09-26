package io.github.cuihairu.redis.streaming.config.impl;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression tests for B-27: {@code generateVersion()} split its "same millisecond"
 * decision across unsynchronized static state, so concurrent calls could emit the same
 * version string twice — the else branch's late {@code SEQ.set(0)} reset sequences an
 * if-branch caller had already consumed, and a caller whose clock lagged the shared
 * high-water mark ({@code now < last}) took the else branch and returned that past
 * millisecond's "-0" again. Two RedisConfigService instances in one JVM share the static
 * state, so any fix must serialize class-wide.
 */
class ConfigVersionGeneratorUniquenessTest {

    private static RedisConfigService newService() {
        return new RedisConfigService(mock(RedissonClient.class));
    }

    /** Reflection seam: works whether the generator is an instance or a static method. */
    private static Method versionGenerator() throws Exception {
        Method m = RedisConfigService.class.getDeclaredMethod("generateVersion");
        m.setAccessible(true);
        return m;
    }

    private static void setLastTimestampHighWaterMark(long value) throws Exception {
        Field f = RedisConfigService.class.getDeclaredField("LAST_TS");
        f.setAccessible(true);
        ((AtomicLong) f.get(null)).set(value);
    }

    @Test
    void clockLaggingTheHighWaterMarkDoesNotReuseVersions() throws Exception {
        RedisConfigService service = newService();
        Method generate = versionGenerator();
        long before = highWaterMark();
        try {
            // push the shared high-water mark into the future, so this thread's
            // currentTimeMillis() is "behind" — the exact cross-thread/core skew shape
            setLastTimestampHighWaterMark(System.currentTimeMillis() + 50_000);

            String first = (String) generate.invoke(service);
            String second = (String) generate.invoke(service);

            assertTrue(!first.equals(second),
                    "two versions issued in the same millisecond must differ (old code: both were "
                            + first + ")");
        } finally {
            // LAST_TS is static: restore it so this test cannot poison other tests in the JVM
            setLastTimestampHighWaterMark(before);
        }
    }

    private static long highWaterMark() throws Exception {
        Field f = RedisConfigService.class.getDeclaredField("LAST_TS");
        f.setAccessible(true);
        return ((AtomicLong) f.get(null)).get();
    }

    @Test
    void concurrentGenerationProducesDistinctVersions() throws Exception {
        RedisConfigService service = newService();
        Method generate = versionGenerator();
        int threads = 16;
        int callsPerThread = 4000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit((Callable<List<String>>) () -> {
                    List<String> out = new ArrayList<>(callsPerThread);
                    start.await(30, TimeUnit.SECONDS);
                    for (int i = 0; i < callsPerThread; i++) {
                        out.add((String) generate.invoke(service));
                    }
                    return out;
                }));
            }
            start.countDown();

            Set<String> seen = new HashSet<>();
            List<String> duplicates = new CopyOnWriteArrayList<>();
            for (Future<List<String>> future : futures) {
                for (String version : future.get(60, TimeUnit.SECONDS)) {
                    if (!seen.add(version)) {
                        duplicates.add(version);
                    }
                }
            }
            assertTrue(duplicates.isEmpty(),
                    "versions must be globally unique, got " + duplicates.size() + " duplicates, e.g. "
                            + duplicates.subList(0, Math.min(5, duplicates.size())));
            assertEquals((long) threads * callsPerThread, seen.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sequentialGenerationProducesDistinctVersions() throws Exception {
        RedisConfigService service = newService();
        Method generate = versionGenerator();

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            seen.add((String) generate.invoke(service));
        }
        assertEquals(2000, seen.size());
    }
}
