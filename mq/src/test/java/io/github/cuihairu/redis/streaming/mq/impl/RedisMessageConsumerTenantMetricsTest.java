package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterService;
import io.github.cuihairu.redis.streaming.mq.lease.LeaseManager;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetricsCollector;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.Codec;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The consumer must report topic-scoped counters through the tenant-aware SPI
 * variants with its configured tenant — matching the producer side. A
 * legacy-signature call silently drops the dimension at the SPI boundary and a
 * tenant-scoped collector cannot separate the streams.
 */
class RedisMessageConsumerTenantMetricsTest {

    /** Records only the tenant-aware variants; legacy calls land in the no-op defaults. */
    private static final class TenantRecordingCollector implements MqMetricsCollector {
        final List<String> consumed = new CopyOnWriteArrayList<>();
        final List<String> acked = new CopyOnWriteArrayList<>();
        final List<String> deadLettered = new CopyOnWriteArrayList<>();
        final List<String> retried = new CopyOnWriteArrayList<>();
        final List<String> latencies = new CopyOnWriteArrayList<>();

        @Override
        public void incConsumed(String tenant, String topic, int partitionId) { consumed.add(tenant); }

        @Override
        public void incAcked(String tenant, String topic, int partitionId) { acked.add(tenant); }

        @Override
        public void incDeadLetter(String tenant, String topic, int partitionId) { deadLettered.add(tenant); }

        @Override
        public void incRetried(String tenant, String topic, int partitionId) { retried.add(tenant); }

        @Override
        public void recordHandleLatency(String tenant, String topic, int partitionId, long millis) {
            latencies.add(tenant);
        }

        // legacy signatures are abstract in the SPI; keep them no-ops so a legacy
        // call from the consumer leaves the tenant lists empty and the test fails
        @Override
        public void incProduced(String topic, int partitionId) { }

        @Override
        public void incConsumed(String topic, int partitionId) { }

        @Override
        public void incAcked(String topic, int partitionId) { }

        @Override
        public void incRetried(String topic, int partitionId) { }

        @Override
        public void incDeadLetter(String topic, int partitionId) { }

        @Override
        public void recordHandleLatency(String topic, int partitionId, long millis) { }
    }

    private RedissonClient client;
    private RStream<String, Object> dataStream;
    private Broker broker;
    private RedisMessageConsumer consumer;
    private DeadLetterService deadLetterService;
    private MqMetricsCollector previousCollector;
    private TenantRecordingCollector metrics;

    private static final Class<?>[] DISPATCH = {String.class, String.class, String.class, int.class,
            Message.class, MessageHandleResult.class, Map.class, RStream.class};
    private static final Class<?>[] PROCESS = {String.class, String.class, int.class, String.class,
            Map.class, RStream.class, MessageHandler.class, boolean.class};

    @BeforeEach
    void setUp() throws Exception {
        previousCollector = MqMetrics.get();
        metrics = new TenantRecordingCollector();
        MqMetrics.setCollector(metrics);
        client = mock(RedissonClient.class);
        dataStream = mock(RStream.class);
        RMap frontier = mock(RMap.class);
        RMap retryItem = mock(RMap.class);
        RScoredSortedSet retryBucket = mock(RScoredSortedSet.class);
        RScript script = mock(RScript.class);
        RLock lock = mock(RLock.class);
        broker = mock(Broker.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);

        when(client.getStream(anyString(), any(Codec.class))).thenReturn((RStream) dataStream);
        when(client.getMap(anyString())).thenReturn((RMap) frontier);
        when(client.getMap(anyString(), any(Codec.class))).thenReturn((RMap) retryItem);
        when(client.getScoredSortedSet(anyString(), any(Codec.class))).thenReturn((RScoredSortedSet) retryBucket);
        when(client.getScript(any(Codec.class))).thenReturn(script);
        when(client.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(dataStream.add(any())).thenReturn(new StreamMessageId(9, 1));
        when(partitionRegistry.getPartitionCount(anyString())).thenReturn(1);

        consumer = new RedisMessageConsumer(client, "tenant-metrics", partitionRegistry,
                MqOptions.builder().tenant("acme")
                        .retryBaseBackoffMs(0).retryMaxBackoffMs(0)
                        .claimIdleMs(0).claimBatchSize(10).build(), broker);
        setField(consumer, "leaseManager", mock(LeaseManager.class));
        deadLetterService = mock(DeadLetterService.class);
        setField(consumer, "deadLetterService", deadLetterService);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            field(consumer, "running").set(consumer, new AtomicBoolean(false));
            field(consumer, "closed").set(consumer, new AtomicBoolean(false));
            invoke(consumer, "close", new Class<?>[]{});
        } catch (Throwable ignore) {
        }
        MqMetrics.setCollector(previousCollector);
        Thread.interrupted();
    }

    @Test
    void consumedAckedAndLatencyCarryTheConfiguredTenant() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "p");
        data.put("timestamp", Instant.now().toString());
        data.put("headers", new HashMap<String, Object>());
        data.put("partitionId", 0);

        invoke(consumer, "processIncomingRecord", PROCESS, "t", "g", 0, "5-0", data,
                dataStream, (MessageHandler) m -> MessageHandleResult.SUCCESS, false);

        assertEquals(List.of("acme"), metrics.consumed, "incConsumed must carry the tenant");
        assertEquals(List.of("acme"), metrics.acked, "incAcked must carry the tenant");
        assertEquals(List.of("acme"), metrics.latencies, "recordHandleLatency must carry the tenant");
    }

    @Test
    void deadLetterCounterCarriesTheConfiguredTenant() throws Exception {
        invoke(consumer, "dispatchResult", DISPATCH, "t", "g", "5-0", 0,
                message("p", 3, 3), MessageHandleResult.FAIL, new HashMap<>(), dataStream);

        assertEquals(List.of("acme"), metrics.deadLettered, "incDeadLetter must carry the tenant");
    }

    @Test
    void retriedCounterCarriesTheConfiguredTenant() throws Exception {
        invoke(consumer, "dispatchResult", DISPATCH, "t", "g", "5-0", 0,
                message("p", 0, 3), MessageHandleResult.RETRY, new HashMap<>(), dataStream);

        assertEquals(List.of("acme"), metrics.retried, "incRetried must carry the tenant");
    }

    // ===== helpers =====

    private static Message message(String payload, int retryCount, int maxRetries) {
        Message m = new Message();
        m.setId("5-0");
        m.setTopic("t");
        m.setPayload(payload);
        m.setTimestamp(Instant.now());
        m.setRetryCount(retryCount);
        m.setMaxRetries(maxRetries);
        m.setHeaders(new HashMap<>());
        return m;
    }

    private static Field field(Object target, String name) throws Exception {
        Field f = RedisMessageConsumer.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        field(target, name).set(target, value);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = RedisMessageConsumer.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }
}
