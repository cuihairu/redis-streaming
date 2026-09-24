package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.lease.LeaseManager;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetricsCollector;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.PendingEntry;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual (round-2) coverage for RedisMessageConsumer defensive/error branches that are
 * reachable by making mocked collaborators fail at precise points.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisMessageConsumerResidualCoverageTest {

    private RedissonClient client;
    private RStream<String, Object> dlqStream;
    private RStream<String, Object> dataStream;
    private RMap<String, String> frontier;
    private RMap<String, String> retryItem;
    private RBucket<String> bucket;
    private RScoredSortedSet<String> retryBucket;
    private RScript script;
    private RLock lock;
    private LeaseManager lease;
    private TopicPartitionRegistry partitionRegistry;
    private RedisMessageConsumer consumer;
    private MqMetricsCollector previousCollector;

    @BeforeEach
    void setUp() throws Exception {
        previousCollector = currentCollector();
        client = mock(RedissonClient.class);
        dlqStream = mock(RStream.class);
        dataStream = mock(RStream.class);
        frontier = mock(RMap.class);
        retryItem = mock(RMap.class);
        bucket = mock(RBucket.class);
        retryBucket = mock(RScoredSortedSet.class);
        script = mock(RScript.class);
        lock = mock(RLock.class);
        partitionRegistry = mock(TopicPartitionRegistry.class);

        when(client.getStream(anyString())).thenReturn((RStream) dlqStream);
        when(client.getStream(anyString(), any(Codec.class))).thenReturn((RStream) dataStream);
        when(client.getMap(anyString())).thenReturn((RMap) frontier);
        when(client.getMap(anyString(), any(Codec.class))).thenReturn((RMap) retryItem);
        when(client.getScoredSortedSet(anyString(), any(Codec.class))).thenReturn((RScoredSortedSet) retryBucket);
        when(client.getBucket(anyString(), any(Codec.class))).thenReturn((RBucket) bucket);
        when(client.getScript(any(Codec.class))).thenReturn(script);
        when(client.getLock(anyString())).thenReturn(lock);
        when(dlqStream.add(any())).thenReturn(new StreamMessageId(9, 0));
        when(dataStream.add(any())).thenReturn(new StreamMessageId(9, 1));
        // throttle default reads so any worker thread cannot busy-spin on mocked reads
        when(dataStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> {
                    Thread.sleep(10);
                    return new LinkedHashMap<StreamMessageId, Map<String, Object>>();
                });
        when(partitionRegistry.getPartitionCount(anyString())).thenReturn(1);

        consumer = new RedisMessageConsumer(client, "unit-residual", partitionRegistry,
                MqOptions.builder().retryBaseBackoffMs(0).retryMaxBackoffMs(0)
                        .claimIdleMs(0).claimBatchSize(10).build());
        lease = mock(LeaseManager.class);
        setField(consumer, "leaseManager", lease);
    }

    @AfterEach
    void tearDown() throws Exception {
        running().set(false);
        pausedField().set(false);
        try {
            invoke(consumer, "close", new Class<?>[]{});
        } catch (Throwable ignore) {
        }
        MqMetrics.setCollector(previousCollector);
        Thread.interrupted();
        org.mockito.Mockito.reset(client, dlqStream, dataStream, frontier, retryItem, bucket, retryBucket,
                script, lock, lease, partitionRegistry);
    }

    // ===== helpers =====

    private static MqMetricsCollector currentCollector() {
        return MqMetrics.get();
    }

    private static final MqMetricsCollector NOOP = new MqMetricsCollector() {
        @Override public void incProduced(String topic, int partitionId) {}
        @Override public void incConsumed(String topic, int partitionId) {}
        @Override public void incAcked(String topic, int partitionId) {}
        @Override public void incRetried(String topic, int partitionId) {}
        @Override public void incDeadLetter(String topic, int partitionId) {}
        @Override public void recordHandleLatency(String topic, int partitionId, long millis) {}
    };

    private static final MqMetricsCollector THROWING = new MqMetricsCollector() {
        @Override public void incProduced(String topic, int partitionId) { throw new IllegalStateException("m1"); }
        @Override public void incConsumed(String topic, int partitionId) { throw new IllegalStateException("m2"); }
        @Override public void incAcked(String topic, int partitionId) { throw new IllegalStateException("m3"); }
        @Override public void incRetried(String topic, int partitionId) { throw new IllegalStateException("m4"); }
        @Override public void incDeadLetter(String topic, int partitionId) { throw new IllegalStateException("m5"); }
        @Override public void recordHandleLatency(String topic, int partitionId, long millis) { throw new IllegalStateException("m6"); }
        @Override public void incPayloadMissing(String topic, int partitionId) { throw new IllegalStateException("m7"); }
        @Override public void setInFlight(String consumerName, long inFlight, int maxInFlight) { throw new IllegalStateException("m8"); }
        @Override public void recordBackpressureWait(String consumerName, long waitMillis) { throw new IllegalStateException("m9"); }
        @Override public void setEligiblePartitions(String c, String t, String g, int n) { throw new IllegalStateException("m10"); }
        @Override public void setLeasedPartitions(String c, String t, String g, int n) { throw new IllegalStateException("m11"); }
        @Override public void setMaxLeasedPartitions(String c, int n) { throw new IllegalStateException("m12"); }
    };

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
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

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = RedisMessageConsumer.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = RedisMessageConsumer.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private AtomicBoolean running() throws Exception {
        return (AtomicBoolean) getField(consumer, "running");
    }

    private AtomicBoolean pausedField() throws Exception {
        return (AtomicBoolean) getField(consumer, "paused");
    }

    private static Object newPartitionWorker(String topic, String group, int pid, MessageHandler handler) throws Exception {
        Class<?> cls = Class.forName(RedisMessageConsumer.class.getName() + "$PartitionWorker");
        Constructor<?> c = cls.getDeclaredConstructor(String.class, String.class, int.class, MessageHandler.class);
        c.setAccessible(true);
        return c.newInstance(topic, group, pid, handler);
    }

    private static Object newPartitionKey(String topic, String group, int pid) throws Exception {
        Class<?> cls = Class.forName(RedisMessageConsumer.class.getName() + "$PartitionKey");
        Constructor<?> c = cls.getDeclaredConstructor(String.class, String.class, int.class);
        c.setAccessible(true);
        return c.newInstance(topic, group, pid);
    }

    private static Object newSubscription(String topic, String group, MessageHandler handler) throws Exception {
        Class<?> cls = Class.forName(RedisMessageConsumer.class.getName() + "$Subscription");
        Constructor<?> c = cls.getDeclaredConstructor(String.class, String.class, MessageHandler.class);
        c.setAccessible(true);
        return c.newInstance(topic, group, handler);
    }

    private static Class<?> partitionWorkerClass() throws Exception {
        return Class.forName(RedisMessageConsumer.class.getName() + "$PartitionWorker");
    }

    private static final Class<?>[] HANDLE_MISSING_4 = {String.class, String.class, int.class, String.class, Map.class};
    private static final Class<?>[] REQUEUE = {RStream.class, String.class, String.class, int.class, Message.class, Map.class};
    private static final Class<?>[] DISPATCH = {String.class, String.class, String.class, int.class, Message.class, MessageHandleResult.class, Map.class, RStream.class};
    private static final Class<?>[] ACK = {String.class, String.class, int.class, RStream.class, String.class, Map.class};
    private static final Class<?>[] PROCESS_INCOMING = {String.class, String.class, int.class, String.class, Map.class, RStream.class, MessageHandler.class, boolean.class};

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

    /** Map whose {@code get} always throws. */
    private static Map<String, Object> throwingData() {
        return new HashMap<String, Object>() {
            @Override
            public Object get(Object key) {
                throw new IllegalStateException("map boom");
            }
        };
    }

    /** Map whose {@code get} throws only for {@code headers}. */
    private static Map<String, Object> headersThrowingData() {
        return new HashMap<String, Object>() {
            @Override
            public Object get(Object key) {
                if ("headers".equals(key)) {
                    throw new IllegalStateException("headers boom");
                }
                return super.get(key);
            }
        };
    }

    /** HashMap whose {@code get} throws (still instanceof HashMap). */
    private static Map<String, String> throwingHeaders() {
        return new HashMap<String, String>() {
            @Override
            public String get(Object key) {
                throw new IllegalStateException("hdr boom");
            }
        };
    }

    private MessageHandler okHandler() {
        return msg -> MessageHandleResult.SUCCESS;
    }

    // ===== subscribe / unsubscribe =====

    @Test
    void subscribeSwallowsEnsureTopicFailure() throws Exception {
        doThrow(new IllegalStateException("ensure boom")).when(partitionRegistry).ensureTopic(anyString(), anyInt());
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(), any()))
                .thenReturn("OK");
        assertDoesNotThrow(() -> consumer.subscribe("t", "g", okHandler()));
    }

    @Test
    @SuppressWarnings("rawtypes")
    void unsubscribeStopsInjectedWorkers() throws Exception {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(), any()))
                .thenReturn("OK");
        consumer.subscribe("t", "g", okHandler());
        Object worker = newPartitionWorker("t", "g", 0, okHandler());
        Object key = newPartitionKey("t", "g", 0);
        ((Map) getField(consumer, "workers")).put(key, worker);
        consumer.unsubscribe("t");
        assertTrue(((Map<?, ?>) getField(consumer, "workers")).isEmpty());
    }

    // ===== stop / close metric & interrupt edges =====

    @Test
    void stopSwallowsMetricCollectorFailures() throws Exception {
        MqMetrics.setCollector(THROWING);
        running().set(true);
        assertDoesNotThrow(() -> consumer.stop());
    }

    @Test
    void closeWhileInterruptedCoversInterruptPath() throws Exception {
        // block the pools so awaitTermination really waits (and then trips on the interrupt)
        ((java.util.concurrent.ExecutorService) getField(consumer, "consumerPool"))
                .submit(() -> {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignore) {
                    }
                });
        ((java.util.concurrent.ExecutorService) getField(consumer, "schedulerPool"))
                .submit(() -> {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignore) {
                    }
                });
        Thread.currentThread().interrupt();
        consumer.close();
        assertTrue(Thread.interrupted());
    }

    // ===== runPartitionWorker residual branches =====

    @Test
    void runPartitionWorkerPausedInterruptedReleasesLease() throws Exception {
        doThrow(new IllegalStateException("release boom")).when(lease).releaseIfOwner(anyString(), anyString());
        pausedField().set(true);
        running().set(true);
        Object worker = newPartitionWorker("t", "g", 0, okHandler());
        Thread runner = new Thread(() -> {
            try {
                invoke(consumer, "runPartitionWorker", new Class<?>[]{partitionWorkerClass()}, worker);
            } catch (Exception ignore) {
            }
        });
        runner.start();
        Thread.sleep(120);
        runner.interrupt();
        runner.join(3000);
        assertTrue(!runner.isAlive());
    }

    @Test
    void runPartitionWorkerErrorSleepInterrupted() throws Exception {
        when(dataStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenThrow(new IllegalStateException("read boom"));
        running().set(true);
        Object worker = newPartitionWorker("t", "g", 0, okHandler());
        Thread runner = new Thread(() -> {
            try {
                invoke(consumer, "runPartitionWorker", new Class<?>[]{partitionWorkerClass()}, worker);
            } catch (Exception ignore) {
            }
        });
        runner.start();
        Thread.sleep(120);
        runner.interrupt();
        runner.join(3000);
        assertTrue(!runner.isAlive());
    }

    // ===== processPendingMessages residual branches =====

    private void installWorkerAndPending(PendingEntry pe, Object claimedOrThrow) throws Exception {
        Object worker = newPartitionWorker("t", "g", 0, okHandler());
        Object key = newPartitionKey("t", "g", 0);
        ((Map) getField(consumer, "workers")).put(key, worker);
        running().set(true);
        when(dataStream.listPending(anyString(), any(StreamMessageId.class), any(StreamMessageId.class), anyInt()))
                .thenReturn(List.of(pe));
        if (claimedOrThrow instanceof RuntimeException re) {
            when(dataStream.claim(anyString(), anyString(), anyLong(), any(), any(StreamMessageId.class)))
                    .thenThrow(re);
        } else {
            when(dataStream.claim(anyString(), anyString(), anyLong(), any(), any(StreamMessageId.class)))
                    .thenReturn((Map<StreamMessageId, Map<String, Object>>) claimedOrThrow);
        }
    }

    @Test
    void processPendingClaimFailureIsSwallowed() throws Exception {
        PendingEntry pe = mock(PendingEntry.class);
        when(pe.getId()).thenReturn(new StreamMessageId(5, 0));
        when(pe.getIdleTime()).thenReturn(10_000L);
        installWorkerAndPending(pe, new IllegalStateException("claim boom"));
        assertDoesNotThrow(() -> invoke(consumer, "processPendingMessages", new Class<?>[]{}));
    }

    @Test
    void processPendingParseRethrowIsCaughtByClaimGuard() throws Exception {
        PendingEntry pe = mock(PendingEntry.class);
        when(pe.getId()).thenReturn(new StreamMessageId(5, 0));
        when(pe.getIdleTime()).thenReturn(10_000L);
        Map<StreamMessageId, Map<String, Object>> claimed = new LinkedHashMap<>();
        claimed.put(new StreamMessageId(5, 0), throwingData());
        installWorkerAndPending(pe, claimed);
        assertDoesNotThrow(() -> invoke(consumer, "processPendingMessages", new Class<?>[]{}));
    }

    @Test
    void processPendingListPendingFailureIsSwallowed() throws Exception {
        Object worker = newPartitionWorker("t", "g", 0, okHandler());
        ((Map) getField(consumer, "workers")).put(newPartitionKey("t", "g", 0), worker);
        running().set(true);
        when(dataStream.listPending(anyString(), any(StreamMessageId.class), any(StreamMessageId.class), anyInt()))
                .thenThrow(new IllegalStateException("pending boom"));
        assertDoesNotThrow(() -> invoke(consumer, "processPendingMessages", new Class<?>[]{}));
    }

    // ===== processIncomingRecord residual branches =====

    @Test
    void precheckRoutesMissingPayloadRefToDlq() throws Exception {
        Map<String, Object> data = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "missing:ref");
        data.put("headers", headers);
        data.put("retryCount", "1");
        data.put("maxRetries", "2");

        when(bucket.isExists()).thenReturn(false);
        MessageHandler handler = mock(MessageHandler.class);
        assertDoesNotThrow(() -> invoke(consumer, "processIncomingRecord", PROCESS_INCOMING,
                "t", "g", 0, "5-0", data, null, handler, true));
        verify(dlqStream).add(any());
        verify(dataStream).ack(eq("g"), eq(new StreamMessageId(5, 0)));

        when(bucket.isExists()).thenReturn(true);
        when(bucket.get()).thenReturn(null);
        assertDoesNotThrow(() -> invoke(consumer, "processIncomingRecord", PROCESS_INCOMING,
                "t", "g", 0, "5-1", data, null, handler, true));
    }

    @Test
    void precheckBucketAccessFailureIsIgnored() throws Exception {
        Map<String, Object> data = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "missing:ref2");
        data.put("headers", headers);
        when(bucket.isExists()).thenThrow(new IllegalStateException("bucket boom"));
        MessageHandler handler = mock(MessageHandler.class);
        when(handler.handle(any())).thenReturn(MessageHandleResult.SUCCESS);
        assertDoesNotThrow(() -> invoke(consumer, "processIncomingRecord", PROCESS_INCOMING,
                "t", "g", 0, "5-0", data, dataStream, handler, true));
    }

    @Test
    void precheckExtractRefFailureIsIgnoredThenParseRethrows() {
        MessageHandler handler = mock(MessageHandler.class);
        when(handler.handle(any())).thenReturn(MessageHandleResult.SUCCESS);
        // the precheck swallows the hostile map failure, but parse re-throws it afterwards
        assertThrows(Exception.class, () -> invoke(consumer, "processIncomingRecord", PROCESS_INCOMING,
                "t", "g", 0, "5-0", headersThrowingData(), dataStream, handler, true));
    }

    @Test
    void nonPayloadParseErrorsAreRethrown() {
        MessageHandler handler = mock(MessageHandler.class);
        Exception e = assertThrows(Exception.class, () -> invoke(consumer, "processIncomingRecord", PROCESS_INCOMING,
                "t", "g", 0, "5-0", throwingData(), dataStream, handler, false));
        assertTrue(e instanceof IllegalStateException);
    }

    @Test
    void numericAndBrokenPartitionIdHeaderVariants() throws Exception {
        MessageHandler handler = mock(MessageHandler.class);
        when(handler.handle(any())).thenReturn(MessageHandleResult.SUCCESS);

        Map<String, Object> numeric = new HashMap<>();
        numeric.put("payload", "p");
        numeric.put("partitionId", 7);
        assertDoesNotThrow(() -> invoke(consumer, "processIncomingRecord", PROCESS_INCOMING,
                "t", "g", 0, "5-0", numeric, dataStream, handler, false));

        Map<String, Object> broken = new HashMap<>();
        broken.put("payload", "p");
        broken.put("partitionId", "not-a-number");
        assertDoesNotThrow(() -> invoke(consumer, "processIncomingRecord", PROCESS_INCOMING,
                "t", "g", 0, "5-1", broken, dataStream, handler, false));
    }

    // ===== handleMissingPayload / DLQ residual =====

    @Test
    void handleMissingPayloadToleratesBadCountersAndStringHeaders() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("key", "k");
        data.put("retryCount", "not-a-number");
        data.put("maxRetries", "also-bad");
        data.put("headers", "{\"a\":\"b\"}");
        assertDoesNotThrow(() -> invoke(consumer, "handleMissingPayload", HANDLE_MISSING_4, "t", "g", 0, "5-0", data));
        verify(dlqStream).add(any());
    }

    @Test
    void handleMissingPayloadMetricFailureIsSwallowed() throws Exception {
        MqMetrics.setCollector(new MqMetricsCollector() {
            @Override public void incProduced(String t, int p) {}
            @Override public void incConsumed(String t, int p) {}
            @Override public void incAcked(String t, int p) {}
            @Override public void incRetried(String t, int p) {}
            @Override public void incDeadLetter(String t, int p) {}
            @Override public void recordHandleLatency(String t, int p, long m) {}
            @Override public void incPayloadMissing(String t, int p) {
                throw new IllegalStateException("payload-missing metric boom");
            }
        });
        Map<String, Object> data = new HashMap<>();
        assertDoesNotThrow(() -> invoke(consumer, "handleMissingPayload", HANDLE_MISSING_4, "t", "g", 0, "5-0", data));
    }

    @Test
    void inFlightMetricFailuresDuringProcessingAreSwallowed() throws Exception {
        MqMetrics.setCollector(new MqMetricsCollector() {
            @Override public void incProduced(String t, int p) {}
            @Override public void incConsumed(String t, int p) {}
            @Override public void incAcked(String t, int p) {}
            @Override public void incRetried(String t, int p) {}
            @Override public void incDeadLetter(String t, int p) {}
            @Override public void recordHandleLatency(String t, int p, long m) {}
            @Override public void setInFlight(String c, long f, int m) {
                throw new IllegalStateException("in-flight metric boom");
            }
        });
        MessageHandler handler = mock(MessageHandler.class);
        when(handler.handle(any())).thenReturn(MessageHandleResult.SUCCESS);
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "p");
        assertDoesNotThrow(() -> invoke(consumer, "processIncomingRecord", PROCESS_INCOMING,
                "t", "g", 0, "5-0", data, dataStream, handler, false));
    }

    @Test
    void handleMissingPayloadStringHeadersParseFailureIsSwallowed() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("headers", "{not-valid-json");
        assertDoesNotThrow(() -> invoke(consumer, "handleMissingPayload", HANDLE_MISSING_4, "t", "g", 0, "5-0", data));
        verify(dlqStream).add(any());
    }

    @Test
    void sendToDeadLetterQueueUsesNowWhenTimestampMissing() throws Exception {
        Message m = message("p", 0, 3);
        m.setTimestamp(null);
        assertDoesNotThrow(() -> invoke(consumer, "sendToDeadLetterQueue", new Class<?>[]{Message.class, int.class}, m, 0));
    }

    // ===== requeueOrDeadLetter residual =====

    @Test
    void requeueWithThrowingHeadersFallsBackToMessageId() throws Exception {
        RedisMessageConsumer slow = new RedisMessageConsumer(client, "unit-slow2", partitionRegistry,
                MqOptions.builder().retryBaseBackoffMs(200).retryMaxBackoffMs(200).build());
        try {
            Message m = message("p", 0, 3);
            m.setHeaders(throwingHeaders());
            assertDoesNotThrow(() -> invoke(slow, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, new HashMap<>()));
        } finally {
            invoke(slow, "close", new Class<?>[]{});
        }
    }

    @Test
    void requeueDirectReenqueueFailureIsSwallowed() throws Exception {
        doThrow(new IllegalStateException("xadd boom")).when(dataStream).add(any());
        Message m = message("p", 0, 3);
        assertDoesNotThrow(() -> invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, new HashMap<>()));
    }

    @Test
    void requeueNullHeadersUsesEmptyJson() throws Exception {
        RedisMessageConsumer slow = new RedisMessageConsumer(client, "unit-slow", partitionRegistry,
                MqOptions.builder().retryBaseBackoffMs(200).retryMaxBackoffMs(200).build());
        try {
            Message m = message("p", 0, 3);
            m.setHeaders(null);
            assertDoesNotThrow(() -> invoke(slow, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, new HashMap<>()));
            verify(retryItem).put(eq("headers"), anyString());
        } finally {
            invoke(slow, "close", new Class<?>[]{});
        }
    }

    // ===== ackViaBackend residual =====

    @Test
    void ackPayloadCleanupFailureIsSwallowed() throws Exception {
        assertDoesNotThrow(() -> invoke(consumer, "ackViaBackend", ACK, "t", "g", 0, dataStream, "5-0", throwingData()));
    }

    // ===== moveDueRetries residual =====

    private void installSubscription(String topic) throws Exception {
        ((Map) getField(consumer, "subscriptions")).put(topic, newSubscription(topic, "g", okHandler()));
    }

    @Test
    void moveDueRetriesSkipsWhenLockHeld() throws Exception {
        installSubscription("t");
        running().set(true);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);
        assertDoesNotThrow(() -> invoke(consumer, "moveDueRetries", new Class<?>[]{}));
    }

    @Test
    void moveDueRetriesEvalFailureIsSwallowedAndUnlocks() throws Exception {
        installSubscription("t");
        running().set(true);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(),
                org.mockito.ArgumentMatchers.<Object>any()))
                .thenThrow(new IllegalStateException("lua boom"));
        assertDoesNotThrow(() -> invoke(consumer, "moveDueRetries", new Class<?>[]{}));
        verify(lock).unlock();
    }

    // ===== rebalance / renew residual =====

    @Test
    void rebalanceSkipsAlreadyOwnedPartitions() throws Exception {
        installSubscription("t");
        running().set(true);
        when(lease.tryAcquire(anyString(), anyString(), anyLong())).thenReturn(true);
        invoke(consumer, "rebalanceAssignments", new Class<?>[]{});
        invoke(consumer, "rebalanceAssignments", new Class<?>[]{});
        running().set(false);
    }

    @Test
    void rebalanceTakesOverWhenAlreadyOwnerAndRenewFails() throws Exception {
        installSubscription("t");
        running().set(true);
        when(lease.tryAcquire(anyString(), anyString(), anyLong())).thenReturn(false);
        when(lease.isOwner(anyString(), anyString())).thenReturn(true);
        doThrow(new IllegalStateException("renew boom")).when(lease).renewIfOwner(anyString(), anyString(), anyLong());
        invoke(consumer, "rebalanceAssignments", new Class<?>[]{});
        running().set(false);
    }

    @Test
    void metricFailuresInRebalanceRenewAndPublishAreSwallowed() throws Exception {
        MqMetrics.setCollector(THROWING);
        installSubscription("t");
        running().set(true);
        when(lease.tryAcquire(anyString(), anyString(), anyLong())).thenReturn(false);
        assertDoesNotThrow(() -> {
            try {
                invoke(consumer, "rebalanceAssignments", new Class<?>[]{});
                invoke(consumer, "renewLeases", new Class<?>[]{});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        running().set(false);
    }

    // ===== dispatchResult residual =====

    @Test
    void dispatchResultToleratesBrokenDeferAckHeaders() throws Exception {
        Message m = message("p", 0, 3);
        m.setHeaders(throwingHeaders());
        assertDoesNotThrow(() -> invoke(consumer, "dispatchResult", DISPATCH,
                "t", "g", "5-0", 0, m, MessageHandleResult.SUCCESS, new HashMap<>(), dataStream));
        verify(dataStream).ack(eq("g"), eq(new StreamMessageId(5, 0)));
    }
}
