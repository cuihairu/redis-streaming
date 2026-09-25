package io.github.cuihairu.redis.streaming.runtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.control.PausableMessageConsumer;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipeline;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineDefinition;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineRunner;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetricsCollector;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.SelectiveFailingMetricsCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branches of {@link RedisStreamExecutionEnvironment}: non-positive checkpoint intervals,
 * subscription option copy arms for null overrides, defer-ack warning arms (null mq options /
 * null drain timeout / non-positive intervals), blank partition-id defer-ack bypass, blank commit
 * frontier bootstrap, {@code annotateRuntimeError} guards, MDC sampling failure/id shapes,
 * null-duration diagnostics and the {@code ok == false} handler arm fed by a mocked runner.
 */
class RedisStreamExecutionEnvironmentGapClosureTest {

    private RedissonClient redisson;
    private RScript script;
    private RMap<Object, Object> map;
    private MessageQueueFactory mqFactory;
    private final List<TestConsumer> consumers = new ArrayList<>();
    private RedisRuntimeMetricsCollector previousCollector;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        map = mock(RMap.class);
        when(redisson.getScript(any(Codec.class))).thenReturn(script);
        when(redisson.getMap(anyString())).thenReturn(map);
        when(redisson.getMap(anyString(), any(Codec.class))).thenReturn(map);
        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class))).thenReturn("OK");
        mqFactory = mock(MessageQueueFactory.class);
        when(mqFactory.createConsumer(anyString())).thenAnswer(inv -> {
            TestConsumer c = new TestConsumer();
            consumers.add(c);
            return c;
        });
        previousCollector = RedisRuntimeMetrics.get();
        RedisRuntimeMetrics.setCollector(new SelectiveFailingMetricsCollector());
    }

    @AfterEach
    void tearDown() {
        RedisRuntimeMetrics.setCollector(previousCollector);
        consumers.forEach(c -> {
        });
    }

    private RedisRuntimeConfig.Builder baseConfig() {
        return RedisRuntimeConfig.builder()
                .jobName("gap-env-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("it-gap-env:" + UUID.randomUUID().toString().substring(0, 6))
                .checkpointKeyPrefix("it-gap-env:cp:" + UUID.randomUUID().toString().substring(0, 6))
                .restoreConsumerGroupFromCommitFrontier(false)
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

    private void runAndCancel(RedisRuntimeConfig cfg) {
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t-" + consumers.size(), "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        job.cancel();
    }

    @Test
    void nonPositiveCheckpointIntervalsDisablePeriodicCheckpoints() throws Exception {
        RedisStreamExecutionEnvironment neg = environment(baseConfig()
                .checkpointInterval(Duration.ofMillis(-5)).build());
        neg.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = neg.executeAsync();
        try {
            TestConsumer c = consumers.get(consumers.size() - 1);
            Thread.sleep(150);
            assertEquals(0, c.pauseCount.get(), "negative interval must not schedule periodic checkpoints");
        } finally {
            job.cancel();
        }

        RedisRuntimeConfig real = baseConfig().build();
        RedisRuntimeConfig mocked = mock(RedisRuntimeConfig.class, delegatesTo(real));
        when(mocked.getCheckpointInterval()).thenReturn(null);
        RedisStreamExecutionEnvironment nul = environment(mocked);
        nul.fromMqTopic("t2", "g").addSink(v -> {
        });
        RedisJobClient job2 = nul.executeAsync();
        try {
            TestConsumer c = consumers.get(consumers.size() - 1);
            Thread.sleep(150);
            assertEquals(0, c.pauseCount.get(), "null interval must not schedule periodic checkpoints");
        } finally {
            job2.cancel();
        }
    }

    @Test
    void optionsForSubtaskSkipsNullOverrides() {
        RedisRuntimeConfig cfg = baseConfig().pipelineParallelism(2).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g", SubscriptionOptions.builder().build()).addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            SubscriptionOptions o0 = consumers.get(0).options;
            assertNull(o0.getBatchCount());
            assertNull(o0.getPollTimeoutMs());
            assertEquals(2, o0.getPartitionModulo());
            assertEquals(0, o0.getPartitionRemainder());
        } finally {
            job.cancel();
        }
    }

    @Test
    void warnDeferAckArmsForNullMqOptionsAndNullDrainTimeout() {
        RedisRuntimeConfig withMq = baseConfig().deferAckUntilCheckpoint(true)
                .checkpointInterval(Duration.ofMillis(200))
                .mqOptions(MqOptions.builder().claimIdleMs(100).build())
                .build();
        RedisRuntimeConfig nullMq = mock(RedisRuntimeConfig.class, delegatesTo(withMq));
        when(nullMq.getMqOptions()).thenReturn(null);
        runAndCancel(nullMq);

        RedisRuntimeConfig withDrain = baseConfig().deferAckUntilCheckpoint(true)
                .checkpointInterval(Duration.ofMillis(500))
                .checkpointDrainTimeout(Duration.ofMillis(50))
                .mqOptions(MqOptions.builder().claimIdleMs(100).build())
                .build();
        RedisRuntimeConfig nullDrain = mock(RedisRuntimeConfig.class, delegatesTo(withDrain));
        when(nullDrain.getCheckpointDrainTimeout()).thenReturn(null);
        runAndCancel(nullDrain);

        RedisRuntimeConfig nullInterval = mock(RedisRuntimeConfig.class, delegatesTo(
                baseConfig().deferAckUntilCheckpoint(true)
                        .mqOptions(MqOptions.builder().claimIdleMs(100).build()).build()));
        when(nullInterval.getCheckpointInterval()).thenReturn(null);
        runAndCancel(nullInterval);

        runAndCancel(baseConfig().deferAckUntilCheckpoint(true)
                .checkpointInterval(Duration.ofMillis(-1))
                .mqOptions(MqOptions.builder().claimIdleMs(5_000).build())
                .checkpointDrainTimeout(Duration.ofMillis(20))
                .build());
    }

    @Test
    void markDeferAckSkipsBlankPartitionId() {
        RedisRuntimeConfig cfg = baseConfig().deferAckUntilCheckpoint(true).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            Message m = message("5-1", new HashMap<>(Map.of(MqHeaders.PARTITION_ID, "   ")));
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(m));
            assertNull(m.getHeaders().get(MqHeaders.DEFER_ACK));
        } finally {
            job.cancel();
        }
    }

    @Test
    void ensureConsumerGroupTreatsBlankFrontierAsZeroStart() {
        when(map.get(any())).thenReturn("   ");
        RedisRuntimeConfig cfg = baseConfig().restoreConsumerGroupFromCommitFrontier(true).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            verify(script).eval(any(), anyString(), any(), anyList(), eq("g"), eq("0-0"));
        } finally {
            job.cancel();
        }
    }

    @Test
    void handlerErrorWithNullMessageAnnotatesNothingAndReturnsConfiguredResult() throws Exception {
        RedisRuntimeConfig real = baseConfig()
                .mdcEnabled(true)
                .mdcSampleRate(1.0d)
                .processingErrorResult(MessageHandleResult.DEAD_LETTER)
                .build();
        RedisRuntimeConfig cfg = mock(RedisRuntimeConfig.class, delegatesTo(real));
        AtomicBoolean armed = new AtomicBoolean();
        when(cfg.isDeferAckUntilCheckpoint()).thenAnswer(inv -> {
            if (armed.get()) {
                throw new IllegalStateException("defer check down");
            }
            return false;
        });
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            armed.set(true);
            assertEquals(MessageHandleResult.DEAD_LETTER, handler.handle(null));
        } finally {
            job.cancel();
        }
    }

    @Test
    void annotateRuntimeErrorWithNullErrorAddsNoErrorHeaders() throws Exception {
        RedisRuntimeConfig cfg = baseConfig().build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        Message m = message("1-1", null);
        Method annotate = RedisStreamExecutionEnvironment.class
                .getDeclaredMethod("annotateRuntimeError", Message.class, String.class, Throwable.class);
        annotate.setAccessible(true);
        annotate.invoke(env, m, "g", null);
        assertEquals(cfg.getJobName(), m.getHeaders().get(RedisRuntimeHeaders.JOB_NAME));
        assertEquals("g", m.getHeaders().get(RedisRuntimeHeaders.CONSUMER_GROUP));
        assertNull(m.getHeaders().get(RedisRuntimeHeaders.ERROR_TYPE), "null error carries no error headers");
    }

    @Test
    void shouldInstallMdcToleratesSampleRateGetterFailure() {
        RedisRuntimeConfig real = baseConfig().mdcEnabled(true).build();
        RedisRuntimeConfig cfg = mock(RedisRuntimeConfig.class, delegatesTo(real));
        when(cfg.getMdcSampleRate()).thenThrow(new IllegalStateException("rate down"));
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(message("1-1", null)));
        } finally {
            job.cancel();
        }
    }

    @Test
    void shouldInstallMdcSamplingCoversAllIdShapes() {
        RedisRuntimeConfig cfg = baseConfig().mdcEnabled(true).mdcSampleRate(0.5d).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            Message origSet = message("a", new HashMap<>(Map.of(MqHeaders.ORIGINAL_MESSAGE_ID, "orig-1")));
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(origSet));
            Message origBlank = message("b", new HashMap<>(Map.of(MqHeaders.ORIGINAL_MESSAGE_ID, "  ")));
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(origBlank));
            Message headersNull = message("c", null);
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(headersNull));
            Message explodingId = new Message() {
                @Override
                public String getId() {
                    throw new IllegalStateException("id down");
                }
            };
            explodingId.setHeaders(new HashMap<>());
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(explodingId));
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(null));
        } finally {
            job.cancel();
        }
    }

    @Test
    void diagnosticsReportsNullDurationsAsZero() {
        RedisRuntimeConfig real = baseConfig().build();
        RedisRuntimeConfig cfg = mock(RedisRuntimeConfig.class, delegatesTo(real));
        when(cfg.getCheckpointInterval()).thenReturn(null);
        when(cfg.getSinkDeduplicationTtl()).thenReturn(null);
        when(cfg.getWatermarkOutOfOrderness()).thenReturn(null);
        when(cfg.getWindowAllowedLateness()).thenReturn(null);
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            Map<String, Object> diag = job.diagnostics();
            assertEquals(0L, diag.get("checkpointIntervalMs"));
            assertEquals(0L, diag.get("sinkDeduplicationTtlMs"));
            assertEquals(0L, diag.get("watermarkOutOfOrdernessMs"));
            assertEquals(0L, diag.get("windowAllowedLatenessMs"));
        } finally {
            job.cancel();
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void handlerReturnsRetryWhenRunnerReportsFailure() throws Exception {
        RedisPipelineDefinition def = mock(RedisPipelineDefinition.class);
        RedisPipeline<Object> pipeline = mock(RedisPipeline.class);
        RedisPipelineRunner<Object> runner = mock(RedisPipelineRunner.class);
        when(def.topic()).thenReturn("t");
        when(def.consumerGroup()).thenReturn("g");
        when(def.freeze()).thenReturn(pipeline);
        when(pipeline.topic()).thenReturn("t");
        when(pipeline.consumerGroup()).thenReturn("g");
        when(pipeline.buildRunner(any())).thenReturn(runner);
        when(runner.handle(any())).thenReturn(false);

        RedisRuntimeConfig cfg = baseConfig().build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.registerPipelineDefinition(def);
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            assertEquals(MessageHandleResult.RETRY, handler.handle(message("1-1", null)));
            verify(runner).handle(any());
        } finally {
            job.cancel();
        }
    }

    private static final class TestConsumer implements MessageConsumer, PausableMessageConsumer {
        volatile MessageHandler handler;
        volatile SubscriptionOptions options;
        final java.util.concurrent.atomic.AtomicInteger pauseCount = new java.util.concurrent.atomic.AtomicInteger();

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
            this.options = options;
        }

        @Override
        public void unsubscribe(String topic) {
        }

        @Override
        public void start() {
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
    }
}
