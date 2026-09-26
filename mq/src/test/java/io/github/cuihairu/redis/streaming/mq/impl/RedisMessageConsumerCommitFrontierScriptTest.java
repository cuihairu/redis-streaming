package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MQ-11 regression: the commit frontier is a max() reduced across concurrent ack
 * workers, so its compare-and-set must run inside Redis as one atomic Lua script —
 * the old Java read-then-put let two workers read the same prev and land the smaller
 * id last, regressing the frontier. Uses only pre-fix public API + reflection, so it
 * reproduces on the old code.
 */
class RedisMessageConsumerCommitFrontierScriptTest {

    private static final Class<?>[] ACK = {String.class, String.class, int.class,
            RStream.class, String.class, Map.class};

    private RedissonClient client;
    private RScript frontierScript;
    private RedisMessageConsumer consumer;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        client = mock(RedissonClient.class);
        frontierScript = mock(RScript.class);
        when(client.getStream(any(String.class), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RStream) mock(RStream.class));
        when(client.getMap(any(String.class))).thenReturn((RMap) mock(RMap.class));
        when(client.getBucket(any(String.class), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RBucket) mock(RBucket.class));
        when(client.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(frontierScript);

        consumer = new RedisMessageConsumer(client, "unit-frontier",
                mock(TopicPartitionRegistry.class), MqOptions.builder().build());
    }

    @AfterEach
    void tearDown() throws Exception {
        RedisMessageConsumer.class.getMethod("close").invoke(consumer);
    }

    private void ack(String messageId) throws Exception {
        Method m = RedisMessageConsumer.class.getDeclaredMethod("ackViaBackend", ACK);
        m.setAccessible(true);
        m.invoke(consumer, "t", "g", 0, null, messageId, null);
    }

    @Test
    void frontierUpdateRunsAsOneAtomicScript() throws Exception {
        ack("5-0");

        verify(frontierScript).eval(eq(RScript.Mode.READ_WRITE), anyString(),
                eq(RScript.ReturnType.LONG),
                eq(Collections.singletonList(StreamKeys.commitFrontier("t", 0))),
                eq("g"), eq("5-0"));
    }

    @Test
    void frontierScriptFailureIsSwallowed() {
        doThrow(new IllegalStateException("lua gone")).when(frontierScript)
                .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                        anyList(), any(Object.class));

        assertDoesNotThrow(() -> ack("5-1"),
                "the frontier update is best-effort — a script failure must not fail the ack");
    }

    /**
     * The race itself, pinned in-process: 8 ack workers push distinct ids through
     * {@code ackViaBackend} against an in-memory stand-in for the Redis hash field.
     * On the fixed code the script answer performs the compare-and-set atomically the
     * way Redis executes the real script, so the frontier ends at the max id. On the
     * old code the same workers race a non-atomic get-then-put and can land a smaller
     * id last (this file's other two tests are the precise old-code discriminators —
     * the eval contract and the swallowed failure — when the race window is not hit).
     */
    @Test
    void concurrentAcksEndAtTheMaxId() throws Exception {
        Map<String, String> store = new ConcurrentHashMap<>();
        @SuppressWarnings("unchecked")
        RMap<String, String> frontier = (RMap) mock(RMap.class);
        when(client.getMap(anyString())).thenReturn((RMap) frontier);
        // old-code path: read-then-put on the hash, mirrored 1:1 into the store
        when(frontier.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        when(frontier.put(anyString(), anyString()))
                .thenAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)));
        // fixed-code path: the script runs as one atomic step in Redis
        doAnswer(inv -> {
            String group = inv.getArgument(4);
            String id = inv.getArgument(5);
            synchronized (store) {
                String prev = store.get(group);
                if (prev == null || compareStreamId(id, prev) > 0) {
                    store.put(group, id);
                }
            }
            return 1L;
        }).when(frontierScript).eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                anyList(), any(Object.class));

        int threads = 8;
        int idsPerThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int worker = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < idsPerThread; i++) {
                    ack("5-" + (worker * idsPerThread + i));
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals("5-" + (threads * idsPerThread - 1), store.get("g"),
                "the frontier must end at the max acked id: a read-modify-write race "
                        + "must not land a smaller id last");
    }

    private static int compareStreamId(String a, String b) {
        String[] pa = a.split("-", 2);
        String[] pb = b.split("-", 2);
        long am = Long.parseLong(pa[0]);
        long bm = Long.parseLong(pb[0]);
        if (am != bm) return am < bm ? -1 : 1;
        long as = pa.length > 1 ? Long.parseLong(pa[1]) : 0L;
        long bs = pb.length > 1 ? Long.parseLong(pb[1]) : 0L;
        return Long.compare(as, bs);
    }
}
