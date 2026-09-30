package io.github.cuihairu.redis.streaming.registry.client.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-42 regression: the 'metrics' JSON is one document changed by read-modify-write,
 * so concurrent updates from the request threads must serialize — without the
 * per-instance lock, parallel {@code incrementInflight} calls lost writes and the
 * counter stuck below its true value. Uses only the public API, so it reproduces on
 * the pre-fix code.
 */
class RedisClientMetricsReporterConcurrencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Backs the hash read (map.get) and the server-side merge script (script.eval) with
     * one in-memory store; the eval answer performs the same merge semantics as the
     * production Lua (overlay the incoming document onto the stored one).
     */
    private static Map<String, String> mockMergeBackedStore(RedissonClient redisson, RMap<String, String> map)
            throws Exception {
        Map<String, String> store = new ConcurrentHashMap<>();
        when(map.get("metrics")).thenAnswer(inv -> store.get("metrics"));
        RScript script = mock(RScript.class);
        when(redisson.getScript(any(StringCodec.class))).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(),
                any(), any()))
                .thenAnswer(inv -> {
                    String incoming = inv.getArgument(5);
                    Map<String, Object> merged = new HashMap<>();
                    String existing = store.get("metrics");
                    if (existing != null) {
                        merged = MAPPER.readValue(existing, new TypeReference<Map<String, Object>>() {});
                    }
                    merged.putAll(MAPPER.readValue(incoming, new TypeReference<Map<String, Object>>() {}));
                    store.put("metrics", MAPPER.writeValueAsString(merged));
                    return 1L;
                });
        return store;
    }

    @Test
    void concurrentInflightUpdatesAreAllCounted() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap(any(String.class), any(StringCodec.class))).thenReturn(map);
        Map<String, String> store = mockMergeBackedStore(redisson, map);

        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setKeyPrefix("registry");
        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(redisson, config);

        int threads = 8;
        int perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    reporter.incrementInflight("svc", "i-1");
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        Map<String, Object> metrics = MAPPER.readValue(
                store.get("metrics"), new TypeReference<Map<String, Object>>() {});
        assertEquals(threads * perThread,
                ((Number) metrics.get("clientInflight")).longValue(),
                "every increment must land: no read-modify-write cycle may be lost");
    }

    @Test
    void balancedConcurrentIncrementsAndDecrementsEndAtZero() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap(any(String.class), any(StringCodec.class))).thenReturn(map);
        Map<String, String> store = mockMergeBackedStore(redisson, map);

        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setKeyPrefix("registry");
        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(redisson, config);

        // two phases: concurrent decrements must not interleave with increments here,
        // because the floor clamp (max(0, ...)) legitimately absorbs them otherwise
        int threads = 4;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int phase = 0; phase < 2; phase++) {
            final boolean increment = phase == 0;
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (increment) {
                            reporter.incrementInflight("svc", "i-1");
                        } else {
                            reporter.decrementInflight("svc", "i-1");
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }
        pool.shutdown();

        Map<String, Object> metrics = MAPPER.readValue(
                store.get("metrics"), new TypeReference<Map<String, Object>>() {});
        assertEquals(0L, ((Number) metrics.get("clientInflight")).longValue(),
                "a balanced workload must return the counter to zero (maxInflight uses this)");
    }
}
