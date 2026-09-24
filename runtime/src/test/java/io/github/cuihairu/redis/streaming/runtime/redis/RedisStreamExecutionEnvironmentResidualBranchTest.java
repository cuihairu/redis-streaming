package io.github.cuihairu.redis.streaming.runtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.CheckpointAwareSink;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.control.PausableMessageConsumer;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetricsCollector;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.SelectiveFailingMetricsCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Residual error/defensive branches of {@link RedisStreamExecutionEnvironment} and its
 * {@code DeferredAcks} helper, driven with mocked Redis/MQ collaborators that fail at precise
 * points: metric-collector failures across the job lifecycle and checkpoint flow, restore-time
 * checkpoint id failures, checked startup failures, periodic checkpoint skip/failure arms,
 * defer-ack header shapes, MDC adapter failures, consumer-group bootstrap failures and
 * deferred-ack write failures.
 */
class RedisStreamExecutionEnvironmentResidualBranchTest {

    private RedissonClient redisson;
    private RScript script;
    private RKeys rkeys;
    private RStream<Object, Object> stream;
    private MessageQueueFactory mqFactory;
    private final List<TestConsumer> consumers = new ArrayList<>();
    private final Map<String, RMap<String, String>> maps = new ConcurrentHashMap<>();
    private final Map<String, RBucket<Object>> buckets = new ConcurrentHashMap<>();
    private final Map<String, RBucket<String>> markers = new ConcurrentHashMap<>();
    private SelectiveFailingMetricsCollector collector;
    private RedisRuntimeMetricsCollector previousCollector;
    private MDCAdapter previousMdcAdapter;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        rkeys = mock(RKeys.class);
        stream = mock(RStream.class);
        when(redisson.getScript(any(Codec.class))).thenReturn(script);
        when(redisson.getKeys()).thenReturn(rkeys);
        when(rkeys.getKeys()).thenReturn(List.of());
        when(redisson.getSet(anyString(), any(Codec.class))).thenReturn((RSet) mock(RSet.class));
        when(redisson.getMap(anyString(), any(Codec.class)))
                .thenAnswer(inv -> mapNamed(inv.getArgument(0)));
        when(redisson.getMap(anyString()))
                .thenAnswer(inv -> mapNamed(inv.getArgument(0)));
        when(redisson.getStream(anyString(), any(Codec.class))).thenReturn(stream);
        when(redisson.getBucket(anyString()))
                .thenAnswer(inv -> buckets.computeIfAbsent(inv.getArgument(0), k -> mock(RBucket.class)));
        when(redisson.getBucket(anyString(), any(Codec.class)))
                .thenAnswer(inv -> markers.computeIfAbsent(inv.getArgument(0), k -> mock(RBucket.class)));
        when(script.eval(any(), anyString(), any(), anyList(), any(), any())).thenReturn("OK");
        mqFactory = mock(MessageQueueFactory.class);
        when(mqFactory.createConsumer(anyString())).thenAnswer(inv -> {
            TestConsumer c = new TestConsumer();
            consumers.add(c);
            return c;
        });

        collector = new SelectiveFailingMetricsCollector();
        previousCollector = RedisRuntimeMetrics.get();
        RedisRuntimeMetrics.setCollector(collector);
        previousMdcAdapter = MDC.getMDCAdapter();
    }

    @AfterEach
    void tearDown() {
        RedisRuntimeMetrics.setCollector(previousCollector);
        installMdcAdapter(previousMdcAdapter);
        consumers.forEach(TestConsumer::release);
    }

    /** {@code MDC.setMDCAdapter} is package-private in slf4j 2.x; reach it reflectively. */
    private static void installMdcAdapter(MDCAdapter adapter) {
        try {
            Method setter = MDC.class.getDeclaredMethod("setMDCAdapter", MDCAdapter.class);
            setter.setAccessible(true);
            setter.invoke(null, adapter);
        } catch (Exception e) {
            throw new IllegalStateException("cannot install MDC adapter", e);
        }
    }

    private RMap<String, String> mapNamed(String name) {
        return maps.computeIfAbsent(name, k -> mock(RMap.class));
    }

    private RedisRuntimeConfig.Builder baseConfig() {
        return RedisRuntimeConfig.builder()
                .jobName("mock-r2-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("it-r2-env:" + UUID.randomUUID().toString().substring(0, 6))
                .checkpointKeyPrefix("it-r2-env:cp:" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build());
    }

    private RedisStreamExecutionEnvironment environment(RedisRuntimeConfig cfg) {
        return RedisStreamExecutionEnvironment.createForTesting(redisson, cfg, mqFactory, new ObjectMapper());
    }

    private static Message message(String id, Map<String, String> headers) {
        Message m = new Message();
        m.setId(id);
        m.setTopic("t");
        m.setPayload("p");
        m.setHeaders(headers);
        return m;
    }

    // ------------------------------------------------------------------ executeAsync arms

    @Test
    void jobLifecycleMetricsFailuresAreSwallowed() {
        collector.failOn("incJobStarted", "incPipelineStarted", "incJobCanceled",
                "recordHandleLatency", "incHandleError");
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopic("t", "g")
                .map(m -> {
                    if ("bad".equals(m.getPayload())) {
                        throw new IllegalStateException("boom");
                    }
                    return "ok";
                })
                .addSink(v -> {
                });
        RedisJobClient job = env.executeAsync();
        MessageHandler handler = consumers.get(0).handler;
        assertEquals(MessageHandleResult.SUCCESS, handler.handle(message("1-1", Map.of("k", "v"))));
        Message bad = message("1-2", Map.of("k", "v"));
        bad.setPayload("bad");
        assertEquals(MessageHandleResult.RETRY, handler.handle(bad));
        job.cancel();
        job.cancel();
    }

    @Test
    void restoredCheckpointIdAccessFailureIsSwallowed() {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("restore-r2")
                .stateKeyPrefix("it-r2-env:restore")
                .checkpointKeyPrefix("it-r2-env:cp:restore")
                .restoreFromLatestCheckpoint(true)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
        String key = cfg.getCheckpointKeyPrefix() + cfg.getJobName() + ":1";
        io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint evil =
                mock(io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint.class);
        io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint.StateSnapshot snapshot =
                mock(io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint.StateSnapshot.class);
        when(evil.getTimestamp()).thenReturn(1L);
        when(evil.getStateSnapshot()).thenReturn(snapshot);
        when(snapshot.getState(anyString())).thenReturn(null);
        when(evil.getCheckpointId()).thenThrow(new IllegalStateException("id boom"));
        when(rkeys.getKeys()).thenReturn(List.of(key));
        RBucket<Object> bucket = buckets.computeIfAbsent(key, k -> mock(RBucket.class));
        when(bucket.get()).thenReturn(evil);

        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        assertNotNull(job);
        job.cancel();
    }

    @Test
    void pipelineStartMetricFailureIsSwallowedAndErrorRethrown() {
        collector.failOn("incPipelineStartFailed");
        TestConsumer failing = new TestConsumer();
        failing.startThrows = true;
        consumers.add(failing);
        when(mqFactory.createConsumer(anyString())).thenReturn(failing);
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RuntimeException e = assertThrows(RuntimeException.class, env::executeAsync);
        assertEquals("start failed", e.getMessage());
    }

    @Test
    void checkedStartupFailureIsWrappedIntoRuntimeException() {
        MessageQueueFactory failingFactory = new CheckedFailingFactory(redisson,
                MqOptions.builder().workerThreads(1).schedulerThreads(1).build());
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.createForTesting(
                redisson, baseConfig().build(), failingFactory, new ObjectMapper());
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RuntimeException e = assertThrows(RuntimeException.class, env::executeAsync);
        assertEquals("Failed to start Redis runtime job", e.getMessage());
        assertTrue(e.getCause() instanceof IOException, "" + e.getCause());
    }

    @Test
    void overlappingPeriodicCheckpointIsSkipped() throws Exception {
        RedisRuntimeConfig cfg = baseConfig().checkpointInterval(Duration.ofMillis(50)).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        TestConsumer consumer = consumers.get(0);
        try {
            consumer.pauseRelease = new CountDownLatch(1);
            Thread manual = new Thread(job::triggerCheckpointNow);
            manual.start();
            assertTrue(consumer.pauseEntered.await(5, TimeUnit.SECONDS));
            Thread.sleep(300);
            consumer.pauseRelease.countDown();
            manual.join(5_000);
        } finally {
            consumer.release();
            job.cancel();
        }
    }

    @Test
    void periodicCheckpointSwallowsPauseFailure() throws Exception {
        RedisRuntimeConfig cfg = baseConfig().checkpointInterval(Duration.ofMillis(50)).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        TestConsumer consumer = consumers.get(0);
        consumer.pauseThrows = true;
        try {
            assertTrue(consumer.pauseEntered.await(5, TimeUnit.SECONDS));
            assertTrue(consumer.pauseCount.get() >= 1);
        } finally {
            job.cancel();
        }
    }

    @Test
    void warnDeferAckToleratesMqOptionsFailure() {
        MqOptions brokenOptions = mock(MqOptions.class);
        when(brokenOptions.getClaimIdleMs()).thenThrow(new IllegalStateException("claim down"));
        RedisRuntimeConfig cfg = baseConfig()
                .deferAckUntilCheckpoint(true)
                .checkpointInterval(Duration.ofMillis(100))
                .mqOptions(brokenOptions)
                .build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        assertNotNull(job);
        job.cancel();
    }

    // ------------------------------------------------------------------ checkpoint flow arms

    @Test
    void successfulCheckpointSwallowsAllMetricFailures() {
        collector.failOn("incCheckpointTriggered", "recordCheckpointDrainDuration",
                "recordCheckpointStoreDuration", "recordCheckpointSinkCommitDuration",
                "incCheckpointCompleted", "recordCheckpointDuration");
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            assertNotNull(job.triggerCheckpointNow());
        } finally {
            job.cancel();
        }
    }

    @Test
    void checkpointStoreFailurePathSwallowsFailureMetric() {
        collector.failOn("incCheckpointFailed");
        when(redisson.getBucket(anyString())).thenThrow(new IllegalStateException("bucket down"));
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            assertNull(job.triggerCheckpointNow());
        } finally {
            job.cancel();
        }
    }

    @Test
    void sinkCommitFailurePathSwallowsFailureMetric() {
        collector.failOn("incCheckpointFailed");
        CheckpointAwareSink<String> sink = new CheckpointAwareSink<>() {
            @Override
            public void invoke(String value) {
            }

            @Override
            public void onCheckpointComplete(long checkpointId) throws Exception {
                throw new IllegalStateException("sink commit down");
            }
        };
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopic("t", "g").map(m -> "x").addSink(sink);
        RedisJobClient job = env.executeAsync();
        try {
            assertNotNull(job.triggerCheckpointNow(), "checkpoint body still returns the stored checkpoint");
        } finally {
            job.cancel();
        }
    }

    // ------------------------------------------------------------------ handler / defer-ack arms

    @Test
    void markDeferAckHandlesVanishingHeadersAndGetterFailures() {
        RedisRuntimeConfig cfg = baseConfig().deferAckUntilCheckpoint(true).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;

            VanishingHeadersMessage vanishing = new VanishingHeadersMessage();
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(vanishing));
            assertEquals("true", vanishing.rawHeaders().get(MqHeaders.DEFER_ACK),
                    "headers must be materialized once the defer-ack flag is set");

            ExplodingIdMessage exploding = new ExplodingIdMessage();
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(exploding));
            assertNull(exploding.rawHeaders().get(MqHeaders.DEFER_ACK));
        } finally {
            job.cancel();
        }
    }

    @Test
    void mdcInstallAndCleanupFailuresAreSwallowed() {
        installMdcAdapter(new DelegatingMdcAdapter(previousMdcAdapter) {
            @Override
            public void put(String key, String val) {
                if ("rs.job".equals(key)) {
                    throw new IllegalStateException("mdc put down");
                }
                super.put(key, val);
            }

            @Override
            public void remove(String key) {
                throw new IllegalStateException("mdc remove down");
            }
        });
        RedisRuntimeConfig cfg = baseConfig().mdcEnabled(true).mdcSampleRate(1.0d).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            assertEquals(MessageHandleResult.SUCCESS,
                    handler.handle(message("5-1", Map.of(MqHeaders.PARTITION_ID, "0"))));
        } finally {
            job.cancel();
        }
    }

    @Test
    void mdcHeaderAndIdLookupFailuresAreSwallowed() {
        RedisRuntimeConfig cfg = baseConfig().mdcEnabled(true).mdcSampleRate(0.5d).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;

            handler.handle(new ExplodingGetMessage());

            handler.handle(new ExplodingIdLookupMessage());

            handler.handle(message("sample-id", null));
        } finally {
            job.cancel();
        }
    }

    @Test
    void handlerErrorWithExplodingHeadersStillReturns() {
        RedisRuntimeConfig cfg = baseConfig().build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").map(m -> {
            throw new IllegalStateException("boom");
        }).addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            assertEquals(MessageHandleResult.RETRY, handler.handle(new ExplodingHeadersMessage()));
        } finally {
            job.cancel();
        }
    }

    @Test
    void mdcInlineGetterFailuresAndNullHeadersAreSwallowed() {
        RedisRuntimeConfig cfg = baseConfig().mdcEnabled(true).mdcSampleRate(1.0d).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(new ExplodingIdMessage()));
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(new ExplodingGetMessage()));
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(message("5-2", null)));
        } finally {
            job.cancel();
        }
    }

    @Test
    void handlerErrorLoggingGetterFailureIsSwallowed() {
        RedisRuntimeConfig cfg = baseConfig().build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").map(m -> {
            throw new IllegalStateException("boom");
        }).addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            assertEquals(MessageHandleResult.RETRY, handler.handle(new ExplodingIdMessage()));
        } finally {
            job.cancel();
        }
    }

    @Test
    void diagnosticsToleratesCheckpointIdAccessFailures() {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("diag-r2")
                .stateKeyPrefix("it-r2-env:diag")
                .checkpointKeyPrefix("it-r2-env:cp:diag")
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
        String key = cfg.getCheckpointKeyPrefix() + cfg.getJobName() + ":1";
        io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint evil =
                mock(io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint.class);
        io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint.StateSnapshot snapshot =
                mock(io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint.StateSnapshot.class);
        when(evil.getTimestamp()).thenReturn(1L);
        when(evil.getStateSnapshot()).thenReturn(snapshot);
        when(snapshot.getState("runtime:meta")).thenReturn(Map.of("sinkCommitted", true));
        when(snapshot.getState("runtime:offsets")).thenReturn(null);
        when(snapshot.getState("runtime:state")).thenReturn(null);
        when(evil.getCheckpointId()).thenThrow(new IllegalStateException("id boom"));
        when(rkeys.getKeys()).thenReturn(List.of(key));
        RBucket<Object> bucket = buckets.computeIfAbsent(key, k -> mock(RBucket.class));
        when(bucket.get()).thenReturn(evil);

        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            Map<String, Object> diag = job.diagnostics();
            assertNotNull(diag);
        } finally {
            job.cancel();
        }
    }

    @Test
    void optionsForSubtaskToleratesOptionGetterFailures() {
        SubscriptionOptions brokenOptions = mock(SubscriptionOptions.class);
        when(brokenOptions.getBatchCount()).thenThrow(new IllegalStateException("batch down"));
        when(brokenOptions.getPollTimeoutMs()).thenThrow(new IllegalStateException("poll down"));
        RedisRuntimeConfig cfg = baseConfig().pipelineParallelism(2).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g", brokenOptions).addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        assertEquals(2, consumers.size());
        job.cancel();
    }

    // ------------------------------------------------------------------ DeferredAcks arms

    @Test
    void ackAllFailureArmsAndSecondRunSkipsDrainedQueues() {
        when(redisson.getStream(anyString(), any(Codec.class))).thenReturn(null);
        RedisRuntimeConfig cfg = baseConfig()
                .deferAckUntilCheckpoint(true)
                .ackDeferredMessagesOnCheckpoint(true)
                .build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            assertEquals(MessageHandleResult.SUCCESS,
                    handler.handle(message("1-1", Map.of(MqHeaders.PARTITION_ID, "0"))));
            when(mapNamed(StreamKeys.commitFrontier("t", 0)).get("g"))
                    .thenThrow(new IllegalStateException("frontier down"));
            assertNotNull(job.triggerCheckpointNow(), "ack failures must not fail the checkpoint");
            assertNotNull(job.triggerCheckpointNow(), "second checkpoint hits the drained queue arm");
        } finally {
            job.cancel();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void deferredAcksSeparatorlessKeysAndEvilQueuesAreHandled() throws Exception {
        Class<?> daClass = Class.forName(
                "io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment$DeferredAcks");
        Constructor<?> ctor = daClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object deferred = ctor.newInstance();
        Field byPipelineField = daClass.getDeclaredField("byPipeline");
        byPipelineField.setAccessible(true);
        Map<String, Map<Integer, Queue<String>>> byPipeline =
                (Map<String, Map<Integer, Queue<String>>>) byPipelineField.get(deferred);

        Map<Integer, Queue<String>> separatorless = new ConcurrentHashMap<>();
        Queue<String> ids = new ConcurrentLinkedQueue<>();
        ids.add("7-1");
        separatorless.put(0, ids);
        byPipeline.put("no-separator", separatorless);

        Method ackAll = daClass.getDeclaredMethod("ackAll", RedissonClient.class);
        ackAll.setAccessible(true);
        ackAll.invoke(deferred, redisson);

        Map<Integer, Queue<String>> evil = new ConcurrentHashMap<>();
        evil.put(0, new EvilQueue());
        byPipeline.put("t|g", evil);
        Method clear = daClass.getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(deferred);
    }

    // ------------------------------------------------------------------ consumer group bootstrap arms

    @Test
    void ensureConsumerGroupSwallowsFrontierAndScriptFailures() {
        when(redisson.getMap(anyString(), any(Codec.class)))
                .thenAnswer(inv -> failingGetMap());
        when(redisson.getMap(anyString()))
                .thenAnswer(inv -> failingGetMap());
        when(script.eval(any(), anyString(), any(), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("lua down"));

        RedisRuntimeConfig cfg = baseConfig().restoreConsumerGroupFromCommitFrontier(true).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        assertNotNull(job);
        job.cancel();
    }

    @SuppressWarnings("unchecked")
    private RMap<String, String> failingGetMap() {
        RMap<String, String> m = mock(RMap.class);
        when(m.get(any())).thenThrow(new IllegalStateException("map get down"));
        return m;
    }

    // ------------------------------------------------------------------ shutdown failure arms

    @Test
    void cancelToleratesRunnerAndExecutorShutdownFailures() throws Exception {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();

        Field runnersField = job.getClass().getDeclaredField("runners");
        runnersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> runners = (List<Object>) runnersField.get(job);
        runners.add(null);

        Field executorField = job.getClass().getDeclaredField("checkpointExecutor");
        executorField.setAccessible(true);
        ScheduledExecutorService brokenExecutor = mock(ScheduledExecutorService.class);
        doThrow(new IllegalStateException("shutdown down")).when(brokenExecutor).shutdownNow();
        executorField.set(job, brokenExecutor);

        job.cancel();
        assertTrue(job.awaitTermination(Duration.ofSeconds(5)));
    }

    // ------------------------------------------------------------------ helpers and evil types

    private static final class CheckedFailingFactory extends MessageQueueFactory {
        CheckedFailingFactory(RedissonClient redisson, MqOptions options) {
            super(redisson, options);
        }

        @Override
        public MessageConsumer createConsumer(String consumerName) {
            return sneakyThrow(new IOException("checked boom"));
        }

        @SuppressWarnings("unchecked")
        static <T, E extends Throwable> T sneakyThrow(Throwable t) throws E {
            throw (E) t;
        }
    }

    /**
     * Headers stay visible until {@code getId()} runs (which markDeferAck does first) and then
     * vanish on the third subsequent read: the read on the line that assigns the local
     * {@code headers} variable must observe {@code null} so the materialization branch runs.
     */
    private static final class VanishingHeadersMessage extends Message {
        private boolean idRead;
        private int headerReadsAfterId;

        VanishingHeadersMessage() {
            setHeaders(new HashMap<>(Map.of(MqHeaders.PARTITION_ID, "0")));
        }

        @Override
        public String getId() {
            idRead = true;
            return "5-1";
        }

        @Override
        public Map<String, String> getHeaders() {
            if (idRead && ++headerReadsAfterId >= 3) {
                return null;
            }
            return super.getHeaders();
        }

        Map<String, String> rawHeaders() {
            return super.getHeaders();
        }
    }

    /** getId() throws inside markDeferAck (covers its swallow-catch). */
    private static final class ExplodingIdMessage extends Message {
        ExplodingIdMessage() {
            setHeaders(new HashMap<>());
        }

        @Override
        public String getId() {
            throw new IllegalStateException("id down");
        }

        @Override
        public String getKey() {
            throw new IllegalStateException("key down");
        }

        Map<String, String> rawHeaders() {
            return super.getHeaders();
        }
    }

    /** getHeaders() throws (covers the runtime-error annotation swallow-catch). */
    private static final class ExplodingHeadersMessage extends Message {
        @Override
        public Map<String, String> getHeaders() {
            throw new IllegalStateException("headers down");
        }
    }

    /** Headers map whose get() throws (covers the MDC header lookup catch). */
    private static final class ExplodingGetMessage extends Message {
        ExplodingGetMessage() {
            Map<String, String> exploding = new HashMap<>() {
                @Override
                public String get(Object key) {
                    throw new IllegalStateException("get down");
                }
            };
            setHeaders(exploding);
        }
    }

    /** Headers absent and getId() throws (covers the MDC id lookup catch). */
    private static final class ExplodingIdLookupMessage extends Message {
        @Override
        public String getId() {
            throw new IllegalStateException("id down");
        }
    }

    private static final class EvilQueue extends ConcurrentLinkedQueue<String> {
        @Override
        public void clear() {
            throw new IllegalStateException("clear down");
        }
    }

    /** Delegating MDC adapter whose mutating hooks can be made to fail. */
    private static class DelegatingMdcAdapter implements MDCAdapter {
        private final MDCAdapter delegate;

        DelegatingMdcAdapter(MDCAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void put(String key, String val) {
            delegate.put(key, val);
        }

        @Override
        public String get(String key) {
            return delegate.get(key);
        }

        @Override
        public void remove(String key) {
            delegate.remove(key);
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public Map<String, String> getCopyOfContextMap() {
            return delegate.getCopyOfContextMap();
        }

        @Override
        public void setContextMap(Map<String, String> contextMap) {
            delegate.setContextMap(contextMap);
        }

        @Override
        public void pushByKey(String key, String value) {
            delegate.pushByKey(key, value);
        }

        @Override
        public String popByKey(String key) {
            return delegate.popByKey(key);
        }

        @Override
        public Deque<String> getCopyOfDequeByKey(String key) {
            return delegate.getCopyOfDequeByKey(key);
        }

        @Override
        public void clearDequeByKey(String key) {
            delegate.clearDequeByKey(key);
        }
    }

    private static final class TestConsumer implements MessageConsumer, PausableMessageConsumer {
        volatile MessageHandler handler;
        volatile boolean startThrows;
        volatile boolean pauseThrows;
        volatile CountDownLatch pauseRelease;
        final CountDownLatch pauseEntered = new CountDownLatch(1);
        final AtomicInteger pauseCount = new AtomicInteger();

        @Override
        public void subscribe(String topic, MessageHandler handler) {
            this.handler = handler;
        }

        @Override
        public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
            this.handler = handler;
        }

        @Override
        public void subscribe(String topic, String consumerGroup, MessageHandler handler, SubscriptionOptions options) {
            this.handler = handler;
        }

        @Override
        public void unsubscribe(String topic) {
        }

        @Override
        public void start() {
            if (startThrows) {
                throw new RuntimeException("start failed");
            }
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void pause() {
            pauseCount.incrementAndGet();
            pauseEntered.countDown();
            CountDownLatch release = pauseRelease;
            if (release != null) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (pauseThrows) {
                throw new RuntimeException("pause failed");
            }
        }

        @Override
        public void resume() {
        }

        @Override
        public boolean isPaused() {
            return false;
        }

        @Override
        public long inFlight() {
            return 0L;
        }

        void release() {
            CountDownLatch release = pauseRelease;
            if (release != null) {
                release.countDown();
            }
        }
    }
}
