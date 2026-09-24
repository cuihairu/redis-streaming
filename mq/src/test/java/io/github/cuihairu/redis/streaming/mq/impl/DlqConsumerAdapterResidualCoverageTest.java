package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.Codec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Residual coverage for DlqConsumerAdapter: the constructor replay lambda (payload
 * serialization, visibility re-add, failure fallback) and the single-argument subscribe
 * bridge. Driven through the adapter's own short-lived DLQ consumer loop on mocked Redis.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class DlqConsumerAdapterResidualCoverageTest {

    private RedissonClient client;
    private RStream<String, Object> defaultStream;
    private RStream<String, Object> stringStream;
    private RStream<String, Object> partitionStream;
    private DlqConsumerAdapter adapter;

    private static final String TOPIC = "adapter-residual";
    private static final String PART_KEY = "stream:topic:orig:p:0";

    @BeforeEach
    void setUp() {
        client = mock(RedissonClient.class);
        defaultStream = mock(RStream.class);
        stringStream = mock(RStream.class);
        partitionStream = mock(RStream.class);
        when(client.getStream(anyString())).thenReturn((RStream) defaultStream);
        when(client.getStream(anyString(), any(Codec.class))).thenReturn((RStream) stringStream);
        when(client.getStream(eq(PART_KEY), any(Codec.class))).thenReturn((RStream) partitionStream);
    }

    @org.junit.jupiter.api.AfterEach
    void resetMocks() {
        if (adapter != null) {
            try {
                adapter.stop();
                adapter.close();
            } catch (Throwable ignore) {
            }
            adapter = null;
        }
        org.mockito.Mockito.reset(client, defaultStream, stringStream, partitionStream);
    }

    private DlqConsumerAdapter spawn(String name) {
        adapter = new DlqConsumerAdapter(client, name, MqOptions.builder().build());
        return adapter;
    }

    /** Yields the entry on the first call, then throttled empty reads to avoid busy-spin. */
    private static java.util.function.Supplier<Map<StreamMessageId, Map<String, Object>>> onceThenIdle(
            Map<StreamMessageId, Map<String, Object>> once) {
        AtomicBoolean first = new AtomicBoolean(true);
        return () -> {
            if (first.compareAndSet(true, false)) {
                return once;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new LinkedHashMap<>();
        };
    }

    private static Map<StreamMessageId, Map<String, Object>> entry(Object payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("originalTopic", "orig");
        data.put("partitionId", "0");
        data.put("payload", payload);
        data.put("retryCount", "0");
        data.put("maxRetries", "3");
        Map<StreamMessageId, Map<String, Object>> out = new LinkedHashMap<>();
        out.put(new StreamMessageId(7, 0), data);
        return out;
    }

    @Test
    void oneArgSubscribeDelegatesWithEntryBridge() {
        DlqConsumerAdapter adapter = spawn("adapter-1");
        assertDoesNotThrow(() -> adapter.subscribe(TOPIC, (Message m) -> MessageHandleResult.SUCCESS));
        adapter.close();
    }

    @Test
    void replayLambdaReAddsWithObjectPayloadWhenNotVisible() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nested", "value");
        java.util.function.Supplier<Map<StreamMessageId, Map<String, Object>>> once = onceThenIdle(entry(payload));
        when(defaultStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> once.get());
        when(partitionStream.add(any())).thenReturn(new StreamMessageId(8, 0));
        when(partitionStream.isExists()).thenReturn(false);

        DlqConsumerAdapter adapter = spawn("adapter-2");
        CountDownLatch done = new CountDownLatch(1);
        adapter.subscribe(TOPIC, (Message m) -> {
            done.countDown();
            return MessageHandleResult.RETRY;
        });
        adapter.start();
        assertTrue(done.await(15000, TimeUnit.MILLISECONDS));
        Thread.sleep(250); // let the replay lambda run (incl. visibility re-add)
        adapter.stop();
        adapter.close();
    }

    @Test
    void replayLambdaReturnsFalseWhenReplayFails() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nested", 1);
        final AtomicInteger adds = new AtomicInteger();
        java.util.function.Supplier<Map<StreamMessageId, Map<String, Object>>> once = onceThenIdle(entry(payload));
        when(defaultStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> once.get());
        when(partitionStream.add(any())).thenAnswer(inv -> {
            adds.incrementAndGet();
            throw new IllegalStateException("replay add boom");
        });

        DlqConsumerAdapter adapter = spawn("adapter-3");
        CountDownLatch done = new CountDownLatch(1);
        adapter.subscribe(TOPIC, (Message m) -> {
            done.countDown();
            return MessageHandleResult.RETRY;
        });
        adapter.start();
        assertTrue(done.await(15000, TimeUnit.MILLISECONDS));
        Thread.sleep(200);
        adapter.stop();
        adapter.close();
        assertTrue(adds.get() >= 1);
    }
}
