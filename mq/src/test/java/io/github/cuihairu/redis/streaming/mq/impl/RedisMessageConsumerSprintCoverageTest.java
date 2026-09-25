package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerRecord;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterRecord;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterService;
import io.github.cuihairu.redis.streaming.mq.lease.LeaseManager;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetricsCollector;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import io.github.cuihairu.redis.streaming.mq.retry.RetryPolicy;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint coverage for the residual RedisMessageConsumer branches: worker loop error/exit
 * paths, requeueOrDeadLetter failure handling, in-flight permit edges, lease renewal via
 * ownership fallback and defensive helpers. Fault injection only through mocked
 * collaborators or subclass seams; no production behavior is asserted as correct unless it
 * actually is.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisMessageConsumerSprintCoverageTest {

    private RedissonClient client;
    private RStream<String, Object> dataStream;
    private RMap<String, String> frontier;
    private RMap<String, String> retryItem;
    private RScoredSortedSet<String> retryBucket;
    private RScript script;
    private RLock lock;
    private LeaseManager lease;
    private Broker broker;
    private TopicPartitionRegistry partitionRegistry;
    private RedisMessageConsumer consumer;
    private DeadLetterService deadLetterService;
    private MqMetricsCollector previousCollector;
    private RecordingCollector metrics;

    private static final Class<?>[] RUN_WORKER = {workerClass()};
    private static final Class<?>[] DISPATCH = {String.class, String.class, String.class, int.class,
            Message.class, MessageHandleResult.class, Map.class, RStream.class};
    private static final Class<?>[] HANDLE_MISSING_5 = {String.class, String.class, int.class, String.class, Map.class};
    private static final Class<?>[] REQUEUE = {RStream.class, String.class, String.class, int.class, Message.class, Map.class};
    private static final Class<?>[] ACK6 = {String.class, String.class, int.class, RStream.class, String.class, Map.class};
    private static final Class<?>[] COMPUTE = {String.class, String.class};
    private static final Class<?>[] PUBLISH = {String.class, String.class, int.class};

    @BeforeEach
    void setUp() throws Exception {
        previousCollector = MqMetrics.get();
        metrics = new RecordingCollector();
        MqMetrics.setCollector(metrics);
        client = mock(RedissonClient.class);
        dataStream = mock(RStream.class);
        frontier = mock(RMap.class);
        retryItem = mock(RMap.class);
        retryBucket = mock(RScoredSortedSet.class);
        script = mock(RScript.class);
        lock = mock(RLock.class);
        broker = mock(Broker.class);
        partitionRegistry = mock(TopicPartitionRegistry.class);

        when(client.getStream(anyString(), any(Codec.class))).thenReturn((RStream) dataStream);
        when(client.getMap(anyString())).thenReturn((RMap) frontier);
        when(client.getMap(anyString(), any(Codec.class))).thenReturn((RMap) retryItem);
        when(client.getScoredSortedSet(anyString(), any(Codec.class))).thenReturn((RScoredSortedSet) retryBucket);
        when(client.getScript(any(Codec.class))).thenReturn(script);
        when(client.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(dataStream.add(any())).thenReturn(new StreamMessageId(9, 1));
        when(dataStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> {
                    Thread.sleep(10);
                    return new LinkedHashMap<StreamMessageId, Map<String, Object>>();
                });
        when(partitionRegistry.getPartitionCount(anyString())).thenReturn(1);

        consumer = new RedisMessageConsumer(client, "sprint", partitionRegistry,
                MqOptions.builder().retryBaseBackoffMs(0).retryMaxBackoffMs(0)
                        .claimIdleMs(0).claimBatchSize(10).build(), broker);
        lease = mock(LeaseManager.class);
        setField(consumer, "leaseManager", lease);
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
        org.mockito.Mockito.reset(client, dataStream, frontier, retryItem, retryBucket, script, lock,
                broker, lease, partitionRegistry, deadLetterService);
    }

    // ===== helpers =====

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

    private static Class<?> workerClass() {
        try {
            return Class.forName(RedisMessageConsumer.class.getName() + "$PartitionWorker");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object newPartitionWorker(String topic, String group, int pid, MessageHandler handler) throws Exception {
        Constructor<?> c = workerClass().getDeclaredConstructor(String.class, String.class, int.class, MessageHandler.class);
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

    private static void workerRunning(Object worker, boolean value) throws Exception {
        ((AtomicBoolean) workerClass().getDeclaredField("running").get(worker)).set(value);
    }

    private static boolean workerRunning(Object worker) throws Exception {
        Field f = workerClass().getDeclaredField("running");
        f.setAccessible(true);
        return ((AtomicBoolean) f.get(worker)).get();
    }

    private Map<Object, Object> workersMap() throws Exception {
        return (Map<Object, Object>) field(consumer, "workers").get(consumer);
    }

    private AtomicBoolean runningField() throws Exception {
        return (AtomicBoolean) field(consumer, "running").get(consumer);
    }

    private AtomicBoolean closedField() throws Exception {
        return (AtomicBoolean) field(consumer, "closed").get(consumer);
    }

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

    private static MessageHandler okHandler() {
        return m -> MessageHandleResult.SUCCESS;
    }

    // ===== runPartitionWorker loop branches =====

    @Test
    void workerLoopSkipsWhenStoppedAndReleasesLease() throws Exception {
        runningField().set(true);
        Object worker = newPartitionWorker("t", "g", 0, okHandler());
        workerRunning(worker, false);
        invoke(consumer, "runPartitionWorker", RUN_WORKER, worker);
        verify(broker, never()).readGroup(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyLong());
        verify(lease).releaseIfOwner(anyString(), eq("sprint"));
    }

    @Test
    void workerLoopSkipsWhenNotRunning() throws Exception {
        runningField().set(false);
        Object worker = newPartitionWorker("t", "g", 0, okHandler());
        invoke(consumer, "runPartitionWorker", RUN_WORKER, worker);
        verify(broker, never()).readGroup(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyLong());
        verify(lease).releaseIfOwner(anyString(), eq("sprint"));
    }

    @Test
    void workerLoopSkipsWhenConsumerClosed() throws Exception {
        runningField().set(true);
        closedField().set(true);
        Object worker = newPartitionWorker("t", "g", 0, okHandler());
        invoke(consumer, "runPartitionWorker", RUN_WORKER, worker);
        verify(broker, never()).readGroup(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyLong());
        verify(lease).releaseIfOwner(anyString(), eq("sprint"));
    }

    @Test
    void workerLoopRecoversAfterReadErrorAndSleeps() throws Exception {
        runningField().set(true);
        Object worker = newPartitionWorker("t", "g", 0, okHandler());
        when(broker.readGroup(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyLong()))
                .thenThrow(new IllegalStateException("read boom"))
                .thenAnswer(inv -> {
                    workerRunning(worker, false);
                    return List.of();
                });
        long start = System.nanoTime();
        invoke(consumer, "runPartitionWorker", RUN_WORKER, worker);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        verify(broker, times(2)).readGroup(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyLong());
        assertTrue(elapsedMs >= 400, "expected the 500ms error backoff to elapse, got " + elapsedMs + "ms");
        verify(lease).releaseIfOwner(anyString(), eq("sprint"));
    }

    @Test
    void workerLoopCatchSkipsBackoffWhenConsumerStopped() throws Exception {
        runningField().set(true);
        Object worker = newPartitionWorker("t", "g", 0, okHandler());
        when(broker.readGroup(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    runningField().set(false);
                    throw new IllegalStateException("read boom");
                });
        invoke(consumer, "runPartitionWorker", RUN_WORKER, worker);
        verify(broker, times(1)).readGroup(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void workerLoopCatchSkipsBackoffWhenClosed() throws Exception {
        runningField().set(true);
        Object worker = newPartitionWorker("t", "g", 0, okHandler());
        when(broker.readGroup(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    closedField().set(true);
                    throw new IllegalStateException("read boom");
                });
        invoke(consumer, "runPartitionWorker", RUN_WORKER, worker);
        verify(broker, times(1)).readGroup(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void workerLoopCatchSkipsBackoffWhenWorkerStopped() throws Exception {
        runningField().set(true);
        Object worker = newPartitionWorker("t", "g", 0, okHandler());
        when(broker.readGroup(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    workerRunning(worker, false);
                    throw new IllegalStateException("read boom");
                });
        invoke(consumer, "runPartitionWorker", RUN_WORKER, worker);
        verify(broker, times(1)).readGroup(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void workerLoopErrorBackoffInterruptBreaksAndReleasesLease() throws Exception {
        runningField().set(true);
        Object worker = newPartitionWorker("t", "g", 0, okHandler());
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        when(broker.readGroup(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    workerThread.set(Thread.currentThread());
                    throw new IllegalStateException("read boom");
                });
        Thread runner = new Thread(() -> {
            try {
                invoke(consumer, "runPartitionWorker", RUN_WORKER, worker);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        runner.start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (workerThread.get() == null && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertNotNull(workerThread.get());
        while (workerThread.get().getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.TIMED_WAITING, workerThread.get().getState());
        runner.interrupt();
        runner.join(5000);
        assertFalse(runner.isAlive());
        assertTrue(workerThread.get().isInterrupted(), "interrupt flag must be restored before break");
        verify(lease).releaseIfOwner(anyString(), eq("sprint"));
    }

    // ===== processPendingMessages guards =====

    @Test
    void processPendingMessagesReturnsWhenNotRunning() throws Exception {
        runningField().set(false);
        invoke(consumer, "processPendingMessages", new Class<?>[]{});
        verify(dataStream, never()).listPending(anyString(), any(StreamMessageId.class), any(StreamMessageId.class), anyInt());
    }

    @Test
    void processPendingMessagesReturnsWhenClosed() throws Exception {
        runningField().set(true);
        closedField().set(true);
        invoke(consumer, "processPendingMessages", new Class<?>[]{});
        verify(dataStream, never()).listPending(anyString(), any(StreamMessageId.class), any(StreamMessageId.class), anyInt());
    }

    // ===== dispatchResult header guard =====

    @Test
    void dispatchSuccessWithNullHeadersStillAcks() throws Exception {
        Message m = message("p", 0, 3);
        m.setHeaders(null);
        invoke(consumer, "dispatchResult", DISPATCH, "t", "g", "5-0", 0, m, MessageHandleResult.SUCCESS,
                new HashMap<String, Object>(), dataStream);
        verify(broker).ack("t", "g", 0, "5-0");
    }

    // ===== handleMissingPayload outer failure and header normalization =====

    @Test
    void handleMissingPayloadSwallowsHostileMessageData() throws Exception {
        Map<String, Object> hostile = new HashMap<String, Object>() {
            @Override
            public Object get(Object key) {
                throw new IllegalStateException("map boom");
            }
        };
        assertDoesNotThrow(() -> invoke(consumer, "handleMissingPayload", HANDLE_MISSING_5,
                "t", "g", 0, "5-0", hostile));
        verify(deadLetterService, never()).send(any());
    }

    @Test
    void handleMissingPayloadSkipsNullHeaderEntries() throws Exception {
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> headers = new HashMap<>();
        headers.put(null, "orphan");
        headers.put("k", null);
        headers.put("a", "b");
        data.put("headers", headers);
        data.put("retryCount", "1");
        data.put("maxRetries", "2");

        invoke(consumer, "handleMissingPayload", HANDLE_MISSING_5, "t", "g", 0, "5-0", data);

        var captor = org.mockito.ArgumentCaptor.forClass(DeadLetterRecord.class);
        verify(deadLetterService).send(captor.capture());
        DeadLetterRecord record = captor.getValue();
        assertEquals("b", record.headers.get("a"));
        assertEquals("true", record.headers.get(io.github.cuihairu.redis.streaming.mq.MqHeaders.PAYLOAD_MISSING));
        assertEquals(2, record.headers.size(), "null-key/null-value header entries must be dropped");
        verify(broker).ack("t", "g", 0, "5-0");
    }

    // ===== requeueOrDeadLetter: exhausted message with failed DLQ write =====

    @Test
    void requeueExhaustedDlqFailureLeavesOriginalUnacked() throws Exception {
        when(deadLetterService.send(any())).thenThrow(new IllegalStateException("dlq down"));
        Message m = message("p", 3, 3);
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, new HashMap<String, Object>());
        verify(deadLetterService).send(any());
        verify(broker, never()).ack(anyString(), anyString(), anyInt(), anyString());
        verify(dataStream, never()).add(any());
    }

    @Test
    void requeueExhaustedDlqSuccessAcksOriginal() throws Exception {
        when(deadLetterService.send(any())).thenReturn(new StreamMessageId(1, 0));
        Message m = message("p", 3, 3);
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, new HashMap<String, Object>());
        verify(deadLetterService).send(any());
        verify(broker).ack("t", "g", 0, "5-0");
    }

    // ===== requeueOrDeadLetter: original-message-id header normalization =====

    @Test
    void requeueTreatsBlankOriginalIdAsMissing() throws Exception {
        Message m = message("p", 0, 3);
        m.getHeaders().put(io.github.cuihairu.redis.streaming.mq.MqHeaders.ORIGINAL_MESSAGE_ID, "   ");
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, new HashMap<String, Object>());
        assertEquals("5-0", m.getHeaders().get(io.github.cuihairu.redis.streaming.mq.MqHeaders.ORIGINAL_MESSAGE_ID));
    }

    // ===== requeueOrDeadLetter: next-retry-exceeds guard via getter/field divergence =====

    /** Exposes a smaller max via the getter while keeping a large field value, reaching the defensive dead-letter guard. */
    private static class ShrinkingMaxMessage extends Message {
        private final int getterMax;

        ShrinkingMaxMessage(int retryCount, int fieldMax, int getterMax) {
            setTopic("t");
            setPayload("p");
            setId("5-0");
            setTimestamp(Instant.now());
            setRetryCount(retryCount);
            setMaxRetries(fieldMax);
            setHeaders(new HashMap<>());
            this.getterMax = getterMax;
        }

        @Override
        public int getMaxRetries() {
            return getterMax;
        }
    }

    @Test
    void requeueDeadLettersWhenReportedMaxIsExceededAndAcks() throws Exception {
        when(deadLetterService.send(any())).thenReturn(new StreamMessageId(1, 0));
        Message m = new ShrinkingMaxMessage(0, 10, 0);
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, new HashMap<String, Object>());
        verify(deadLetterService).send(any());
        verify(broker).ack("t", "g", 0, "5-0");
        verify(dataStream, never()).add(any());
    }

    @Test
    void requeueDeadLettersWhenReportedMaxIsExceededButKeepsUnackedOnDlqFailure() throws Exception {
        when(deadLetterService.send(any())).thenThrow(new IllegalStateException("dlq down"));
        Message m = new ShrinkingMaxMessage(0, 10, 0);
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, new HashMap<String, Object>());
        verify(deadLetterService).send(any());
        verify(broker, never()).ack(anyString(), anyString(), anyInt(), anyString());
    }

    // ===== requeueOrDeadLetter: tiny-backoff payload variants =====

    @Test
    void requeueTinyBackoffWithNullPayloadOmitsPayloadField() throws Exception {
        Message m = message(null, 0, 3);
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, new HashMap<String, Object>());
        var captor = org.mockito.ArgumentCaptor.forClass(org.redisson.api.stream.StreamAddArgs.class);
        verify(dataStream).add(captor.capture());
        Map<String, Object> entry = extractEntries(captor.getValue());
        assertFalse(entry.containsKey("payload"));
        assertEquals(1, entry.get("retryCount"));
    }

    @Test
    void requeueTinyBackoffWithObjectPayloadJsonEncodes() throws Exception {
        Message m = message(null, 0, 3);
        m.setPayload(Map.of("k", "v"));
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, new HashMap<String, Object>());
        var captor = org.mockito.ArgumentCaptor.forClass(org.redisson.api.stream.StreamAddArgs.class);
        verify(dataStream).add(captor.capture());
        Map<String, Object> entry = extractEntries(captor.getValue());
        assertEquals("{\"k\":\"v\"}", entry.get("payload"));
    }

    // ===== requeueOrDeadLetter: retry-bucket payload variants =====

    @Test
    void requeueBucketWithNullAndObjectPayloads() throws Exception {
        setField(consumer, "retryPolicy", new io.github.cuihairu.redis.streaming.mq.retry.ExponentialBackoffRetryPolicy(3, 5000, 10000));

        Message nullPayload = message(null, 0, 3);
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-1", 0, nullPayload, new HashMap<String, Object>());
        verify(retryItem, never()).put(eq("payload"), anyString());

        Message objectPayload = message(null, 0, 3);
        objectPayload.setId("6-0");
        objectPayload.setPayload(Map.of("k", "v"));
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "6-0", 0, objectPayload, new HashMap<String, Object>());
        verify(retryItem).put(eq("payload"), eq("{\"k\":\"v\"}"));
    }

    // ===== requeueOrDeadLetter: headers via overriding Message seams =====

    /** Always reports null headers, no matter how often they are consulted. */
    private static class NullHeadersMessage extends Message {
        NullHeadersMessage(String id) {
            setTopic("t");
            setPayload("p");
            setId(id);
            setTimestamp(Instant.now());
            setRetryCount(0);
            setMaxRetries(3);
        }

        @Override
        public Map<String, String> getHeaders() {
            return null;
        }
    }

    /** Reports a fresh empty header map on every read. */
    private static class FreshEmptyHeadersMessage extends Message {
        FreshEmptyHeadersMessage(String id) {
            setTopic("t");
            setPayload("p");
            setId(id);
            setTimestamp(Instant.now());
            setRetryCount(0);
            setMaxRetries(3);
        }

        @Override
        public Map<String, String> getHeaders() {
            return new HashMap<>();
        }
    }

    @Test
    void requeueTinyBackoffWithNullHeadersSkipsHeadersField() throws Exception {
        Message m = new NullHeadersMessage("5-0");
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, new HashMap<String, Object>());
        var captor = org.mockito.ArgumentCaptor.forClass(org.redisson.api.stream.StreamAddArgs.class);
        verify(dataStream).add(captor.capture());
        Map<String, Object> entry = extractEntries(captor.getValue());
        assertFalse(entry.containsKey("headers"));
    }

    @Test
    void requeueTinyBackoffWithFreshEmptyHeadersSkipsHeadersField() throws Exception {
        Message m = new FreshEmptyHeadersMessage("5-0");
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, new HashMap<String, Object>());
        var captor = org.mockito.ArgumentCaptor.forClass(org.redisson.api.stream.StreamAddArgs.class);
        verify(dataStream).add(captor.capture());
        Map<String, Object> entry = extractEntries(captor.getValue());
        assertFalse(entry.containsKey("headers"));
    }

    @Test
    void requeueBucketWithNullHeadersUsesEmptyJsonAndNoOriginalId() throws Exception {
        setField(consumer, "retryPolicy", new io.github.cuihairu.redis.streaming.mq.retry.ExponentialBackoffRetryPolicy(3, 5000, 10000));
        Message m = new NullHeadersMessage(null);
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, new HashMap<String, Object>());
        verify(retryItem).put(eq("headers"), eq("{}"));
        verify(retryItem).put(eq("originalMessageId"), eq(""));
    }

    @Test
    void requeueBucketWithFreshEmptyHeadersKeepsMessageIdAsOriginal() throws Exception {
        setField(consumer, "retryPolicy", new io.github.cuihairu.redis.streaming.mq.retry.ExponentialBackoffRetryPolicy(3, 5000, 10000));
        Message m = new FreshEmptyHeadersMessage("7-7");
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, new HashMap<String, Object>());
        verify(retryItem).put(eq("headers"), eq("{}"));
        verify(retryItem).put(eq("originalMessageId"), eq("7-7"));
    }

    // ===== requeueOrDeadLetter: outer failure is swallowed without ack =====

    @Test
    void requeueSwallowsRetryPolicyFailureWithoutAck() throws Exception {
        setField(consumer, "retryPolicy", throwingBackoffPolicy());
        Message m = message("p", 0, 3);
        assertDoesNotThrow(() -> invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m,
                new HashMap<String, Object>()));
        verify(broker, never()).ack(anyString(), anyString(), anyInt(), anyString());
        verify(dataStream, never()).add(any());
    }

    private static RetryPolicy throwingBackoffPolicy() {
        return new RetryPolicy() {
            @Override
            public int getMaxAttempts() {
                return 3;
            }

            @Override
            public long nextBackoffMs(int attempt) {
                throw new IllegalStateException("policy boom");
            }
        };
    }

    private static Map<String, Object> extractEntries(Object addArgs) throws Exception {
        for (Class<?> c = addArgs.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (Map<String, Object>) f.get(addArgs);
                }
            }
        }
        throw new IllegalStateException("no entries map found on " + addArgs.getClass());
    }

    // ===== ackViaBackend checked-exception wrap =====

    @Test
    void ackViaBackendWrapsCheckedAckFailure() throws Exception {
        Exception checked = new Exception("checked ack boom");
        doAnswer(inv -> {
            throw checked;
        }).when(broker).ack(anyString(), anyString(), anyInt(), anyString());
        Exception thrown = null;
        try {
            invoke(consumer, "ackViaBackend", ACK6, "t", "g", 0, dataStream, "5-0", new HashMap<String, Object>());
        } catch (Exception e) {
            thrown = e;
        }
        assertNotNull(thrown);
        assertEquals("Ack failed", thrown.getMessage());
        assertEquals(checked, thrown.getCause());
    }

    // ===== in-flight permit edges =====

    /** Delays acquisition so the recorded backpressure wait is non-zero. */
    private static class SlowSemaphore extends Semaphore {
        SlowSemaphore() {
            super(1);
        }

        @Override
        public void acquire() throws InterruptedException {
            Thread.sleep(5);
            super.acquire();
        }
    }

    /** Fails the first acquisition with an interruption after flipping the running flag. */
    private static class InterruptOnceSemaphore extends Semaphore {
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final Runnable onFirst;

        InterruptOnceSemaphore(Runnable onFirst) {
            super(1);
            this.onFirst = onFirst;
        }

        @Override
        public void acquire() throws InterruptedException {
            if (first.compareAndSet(true, false)) {
                onFirst.run();
                throw new InterruptedException("permit interrupted");
            }
            super.acquire();
        }
    }

    /** Rejects releases to exercise the defensive catch. */
    private static class RejectingReleaseSemaphore extends Semaphore {
        RejectingReleaseSemaphore() {
            super(1);
        }

        @Override
        public void release() {
            throw new IllegalStateException("release boom");
        }
    }

    @Test
    void acquirePermitRecordsBackpressureWaitWhenBlocked() throws Exception {
        runningField().set(true);
        setField(consumer, "inFlightLimiter", new SlowSemaphore());
        invoke(consumer, "acquireInFlightPermit", new Class<?>[]{});
        assertTrue(metrics.backpressureWaits.size() >= 1, "backpressure wait must be recorded");
        assertTrue(metrics.backpressureWaits.get(0) > 0);
    }

    @Test
    void acquirePermitRestoresInterruptFlagWhenStoppedWhileInterrupted() throws Exception {
        runningField().set(true);
        Semaphore sem = new InterruptOnceSemaphore(() -> {
            try {
                runningField().set(false);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        setField(consumer, "inFlightLimiter", sem);
        invoke(consumer, "acquireInFlightPermit", new Class<?>[]{});
        assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag must be restored");
    }

    @Test
    void acquirePermitSkipsImmediatelyWhenClosedWithoutInterruptRestore() throws Exception {
        runningField().set(true);
        closedField().set(true);
        Semaphore sem = new Semaphore(1);
        setField(consumer, "inFlightLimiter", sem);
        invoke(consumer, "acquireInFlightPermit", new Class<?>[]{});
        assertEquals(1, sem.availablePermits(), "no permit may be taken once closed");
        assertFalse(Thread.currentThread().isInterrupted(), "no interrupt was pending, so none must be restored");
    }

    @Test
    void releasePermitSwallowsReleaseFailure() throws Exception {
        setField(consumer, "inFlightLimiter", new RejectingReleaseSemaphore());
        assertDoesNotThrow(() -> invoke(consumer, "releaseInFlightPermit", new Class<?>[]{}));
    }

    // ===== moveDueRetries guards =====

    @Test
    void moveDueRetriesEarlyReturnsWithoutLocking() throws Exception {
        runningField().set(false);
        invoke(consumer, "moveDueRetries", new Class<?>[]{});
        runningField().set(true);
        closedField().set(true);
        invoke(consumer, "moveDueRetries", new Class<?>[]{});
        verify(client, never()).getLock(anyString());
    }

    @Test
    void moveDueRetriesHandlesEmptyAndNullMoveResultsAndUnlockFailure() throws Exception {
        runningField().set(true);
        java.util.List<Object> moveResults = new ArrayList<>();
        moveResults.add(null);
        moveResults.add(List.of());
        moveResults.add(List.of("item-1"));
        consumer.subscribe("t", "g", okHandler());
        AtomicBoolean scriptBooted = new AtomicBoolean(false);
        when(script.eval(any(), any(), any(), anyList(), any(Object[].class))).thenAnswer(inv -> {
            if (scriptBooted.compareAndSet(false, true)) {
                return "OK"; // subscribe() group bootstrap
            }
            Object r = moveResults.get(0);
            if (moveResults.size() > 1) {
                moveResults.remove(0);
            }
            return r;
        });
        doThrow(new IllegalStateException("unlock boom")).when(lock).unlock();

        assertDoesNotThrow(() -> invoke(consumer, "moveDueRetries", new Class<?>[]{}));
        assertDoesNotThrow(() -> invoke(consumer, "moveDueRetries", new Class<?>[]{}));
        assertDoesNotThrow(() -> invoke(consumer, "moveDueRetries", new Class<?>[]{}));
        verify(lock, atLeastOnce()).tryLock(anyLong(), anyLong(), any());
    }

    // ===== renewLeases guards =====

    @Test
    void renewLeasesEarlyReturns() throws Exception {
        runningField().set(false);
        invoke(consumer, "renewLeases", new Class<?>[]{});
        runningField().set(true);
        closedField().set(true);
        invoke(consumer, "renewLeases", new Class<?>[]{});
        verify(lease, never()).renewIfOwner(anyString(), anyString(), anyLong());
    }

    // ===== computeEligiblePartitions guards =====

    @Test
    void computeEligiblePartitionsHandlesMissingSubscriptionAndPartialPinning() throws Exception {
        Class<?>[] sig = {String.class, String.class};
        assertEquals(0, invoke(consumer, "computeEligiblePartitions", sig, "nope", "g"));
        consumer.subscribe("t", "g", okHandler());
        assertEquals(0, invoke(consumer, "computeEligiblePartitions", sig, "t", "other-group"));

        Map<String, Object> subs = (Map<String, Object>) field(consumer, "subscriptions").get(consumer);
        Object sub = subs.get("t");
        assertNotNull(sub);
        Class<?> subCls = sub.getClass();
        Field modField = subCls.getDeclaredField("partitionModulo");
        Field remField = subCls.getDeclaredField("partitionRemainder");
        modField.setAccessible(true);
        remField.setAccessible(true);
        modField.set(sub, 2);
        remField.set(sub, null);
        when(partitionRegistry.getPartitionCount("t")).thenReturn(3);
        assertEquals(3, invoke(consumer, "computeEligiblePartitions", sig, "t", "g"));

        remField.set(sub, 1);
        assertEquals(1, invoke(consumer, "computeEligiblePartitions", sig, "t", "g"));
    }

    // ===== publishPartitionMetrics worker accounting =====

    @Test
    void publishPartitionMetricsCountsOnlyMatchingWorkers() throws Exception {
        workersMap().put(newPartitionKey("t", "g", 0), newPartitionWorker("t", "g", 0, okHandler()));
        workersMap().put(newPartitionKey("t", "g2", 1), newPartitionWorker("t", "g2", 1, okHandler()));
        workersMap().put(newPartitionKey("t2", "g", 2), newPartitionWorker("t2", "g", 2, okHandler()));
        invoke(consumer, "publishPartitionMetrics", PUBLISH, "t", "g", 2);
        assertEquals(1, metrics.leasedCounts.size());
        assertEquals(1, metrics.leasedCounts.get(0));
        assertEquals(2, metrics.eligibleCounts.get(0));
    }

    // ===== rebalanceAssignments lease fallback =====

    @Test
    void rebalanceStartsWorkerWhenTryAcquireFailsButOwned() throws Exception {
        runningField().set(true);
        consumer.subscribe("t", "g", okHandler());
        when(lease.tryAcquire(anyString(), eq("sprint"), anyLong())).thenReturn(false);
        when(lease.isOwner(anyString(), eq("sprint"))).thenReturn(true);
        when(lease.renewIfOwner(anyString(), eq("sprint"), anyLong())).thenReturn(true);

        invoke(consumer, "rebalanceAssignments", new Class<?>[]{});
        assertEquals(1, workersMap().size());
    }

    @Test
    void rebalanceStartsWorkerEvenWhenRenewThrows() throws Exception {
        runningField().set(true);
        consumer.subscribe("t", "g", okHandler());
        when(lease.tryAcquire(anyString(), eq("sprint"), anyLong())).thenReturn(false);
        when(lease.isOwner(anyString(), eq("sprint"))).thenReturn(true);
        when(lease.renewIfOwner(anyString(), eq("sprint"), anyLong())).thenThrow(new IllegalStateException("renew boom"));

        invoke(consumer, "rebalanceAssignments", new Class<?>[]{});
        assertEquals(1, workersMap().size());
    }

    // ===== unsubscribe worker filter/removal =====

    @Test
    void unsubscribeStopsOnlyMatchingWorkers() throws Exception {
        consumer.subscribe("tA", "gA", okHandler());
        Object w1 = newPartitionWorker("tA", "gA", 0, okHandler());
        Object w2 = newPartitionWorker("tA", "gB", 1, okHandler());
        Object w3 = newPartitionWorker("tB", "gA", 2, okHandler());
        workersMap().put(newPartitionKey("tA", "gA", 0), w1);
        workersMap().put(newPartitionKey("tA", "gB", 1), w2);
        workersMap().put(newPartitionKey("tB", "gA", 2), w3);

        consumer.unsubscribe("tA");

        assertFalse(workerRunning(w1));
        assertTrue(workerRunning(w2));
        assertTrue(workerRunning(w3));
    }

    /** Map whose removal never yields a value, mimicking a concurrent worker teardown. */
    private static class VanishingWorkerMap extends ConcurrentHashMap<Object, Object> {
        @Override
        public Object remove(Object key) {
            return null;
        }
    }

    @Test
    void unsubscribeToleratesAlreadyRemovedWorker() throws Exception {
        consumer.subscribe("tA", "gA", okHandler());
        Object w1 = newPartitionWorker("tA", "gA", 0, okHandler());
        VanishingWorkerMap vanishing = new VanishingWorkerMap();
        vanishing.put(newPartitionKey("tA", "gA", 0), w1);
        setField(consumer, "workers", vanishing);

        consumer.unsubscribe("tA");

        assertTrue(workerRunning(w1), "worker already gone from map must not be stopped again");
        Map<?, ?> subs = (Map<?, ?>) field(consumer, "subscriptions").get(consumer);
        assertNull(subs.get("tA"));
    }

    // ===== recording collector =====

    private static final class RecordingCollector implements MqMetricsCollector {
        final List<Long> backpressureWaits = new ArrayList<>();
        final List<Integer> leasedCounts = new ArrayList<>();
        final List<Integer> eligibleCounts = new ArrayList<>();

        @Override
        public void incProduced(String topic, int partitionId) {
        }

        @Override
        public void incConsumed(String topic, int partitionId) {
        }

        @Override
        public void incAcked(String topic, int partitionId) {
        }

        @Override
        public void incRetried(String topic, int partitionId) {
        }

        @Override
        public void incDeadLetter(String topic, int partitionId) {
        }

        @Override
        public void recordHandleLatency(String topic, int partitionId, long millis) {
        }

        @Override
        public void recordBackpressureWait(String consumerName, long waitMillis) {
            backpressureWaits.add(waitMillis);
        }

        @Override
        public void setLeasedPartitions(String consumerName, String topic, String consumerGroup, int leasedCount) {
            leasedCounts.add(leasedCount);
        }

        @Override
        public void setEligiblePartitions(String consumerName, String topic, String consumerGroup, int eligibleCount) {
            eligibleCounts.add(eligibleCount);
        }
    }
}
