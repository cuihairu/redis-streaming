package io.github.cuihairu.redis.streaming.runtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Branch coverage for {@link RedisStreamExecutionEnvironment} driven with mocked Redis and MQ
 * plumbing: message handler paths (MDC sampling, defer-ack marking, error annotation),
 * subscription option fan-out, defer-ack warning configuration, checkpoint abort/drain paths,
 * consumer group frontier bootstrap and job client lifecycle edges.
 */
class RedisStreamExecutionEnvironmentMockTest {

    private RedissonClient redisson;
    private RScript script;
    private RMap<Object, Object> map;
    private MessageQueueFactory mqFactory;
    private final List<TestConsumer> consumers = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        map = mock(RMap.class);
        RStream<Object, Object> stream = mock(RStream.class);
        when(redisson.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
        when(redisson.getMap(anyString())).thenReturn(map);
        when(redisson.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(map);
        when(redisson.<Object, Object>getStream(anyString(), any())).thenReturn(stream);
        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class))).thenReturn("OK");
        mqFactory = mock(MessageQueueFactory.class);
        when(mqFactory.createConsumer(anyString())).thenAnswer(inv -> {
            TestConsumer c = new TestConsumer();
            consumers.add(c);
            return c;
        });
    }

    @AfterEach
    void tearDown() {
        consumers.forEach(c -> c.release());
    }

    @Test
    void constructorFallsBackToDefaultsForNullDependencies() {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.createForTesting(
                redisson, null, null, null);
        assertNotNull(env);
        assertThrows(NullPointerException.class,
                () -> RedisStreamExecutionEnvironment.createForTesting(null, baseConfig().build(), mqFactory, null));
    }

    @Test
    void fromMqTopicWithIdValidatesSourceIdAndRejectsDuplicates() {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        assertThrows(NullPointerException.class, () -> env.fromMqTopicWithId(null, "t", "g"));
        assertThrows(IllegalArgumentException.class, () -> env.fromMqTopicWithId("  ", "t", "g"));
        assertThrows(IllegalArgumentException.class, () -> env.fromMqTopicWithId("bad id!", "t", "g"));
        assertNotNull(env.fromMqTopicWithId("ok.id-1", "t", "g"));
        assertThrows(IllegalStateException.class, () -> env.fromMqTopicWithId("ok.id-1", "t", "g"));
    }

    @Test
    void messageHandlerSuccessDeferAckAndMdcPaths() {
        boolean mdcUsable = mdcAdapterStoresValues();
        RedisRuntimeConfig cfg = baseConfig()
                .deferAckUntilCheckpoint(true)
                .mdcEnabled(true)
                .mdcSampleRate(1.0d)
                .build();
        List<String> mdcJobs = new ArrayList<>();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> mdcJobs.add(org.slf4j.MDC.get("rs.job")));
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            Message m = message("5-1", Map.of(MqHeaders.PARTITION_ID, "0"));
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(m));
            assertEquals("true", m.getHeaders().get(MqHeaders.DEFER_ACK));
            assertEquals(1, mdcJobs.size());
            if (mdcUsable) {
                assertEquals(cfg.getJobName(), mdcJobs.get(0), "full sample rate must install MDC");
            }
        } finally {
            job.cancel();
        }
    }

    @Test
    void markDeferAckBranchesForHeaderAndIdShapes() {
        RedisRuntimeConfig cfg = baseConfig().deferAckUntilCheckpoint(true).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;

            Message nullId = message(null, Map.of(MqHeaders.PARTITION_ID, "0"));
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(nullId));
            assertNull(nullId.getHeaders().get(MqHeaders.DEFER_ACK));

            Message blankId = message("  ", new HashMap<>(Map.of(MqHeaders.PARTITION_ID, "0")));
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(blankId));
            assertNull(blankId.getHeaders().get(MqHeaders.DEFER_ACK));

            Message noPartition = message("6-1", new HashMap<>());
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(noPartition));
            assertNull(noPartition.getHeaders().get(MqHeaders.DEFER_ACK));

            Message badPartition = message("6-2", new HashMap<>(Map.of(MqHeaders.PARTITION_ID, "abc")));
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(badPartition));
            assertNull(badPartition.getHeaders().get(MqHeaders.DEFER_ACK));

            Message nullHeaders = message("6-3", null);
            assertNull(nullHeaders.getHeaders());
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(nullHeaders));
            assertNull(nullHeaders.getHeaders(), "no partition id means defer-ack marking bails before touching headers");

            Message immutableHeaders = message("6-4", Map.of(MqHeaders.PARTITION_ID, "0"));
            assertEquals(MessageHandleResult.SUCCESS, handler.handle(immutableHeaders));
            assertEquals("true", immutableHeaders.getHeaders().get(MqHeaders.DEFER_ACK));
            assertInstanceOf(HashMap.class, immutableHeaders.getHeaders());

            assertEquals(MessageHandleResult.SUCCESS, handler.handle(null));
        } finally {
            job.cancel();
        }
    }

    @Test
    void handlerErrorPathAnnotatesAndHonoursConfiguredResult() {
        RedisRuntimeConfig cfg = baseConfig()
                .processingErrorResult(MessageHandleResult.DEAD_LETTER)
                .build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").map(m -> {
            throw new IllegalStateException("boom-" + "x".repeat(600));
        }).addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            Message m = message("5-1", Collections.singletonMap("k", "v"));
            assertEquals(MessageHandleResult.DEAD_LETTER, handler.handle(m));
            assertEquals(cfg.getJobName(), m.getHeaders().get(RedisRuntimeHeaders.JOB_NAME));
            assertEquals("g", m.getHeaders().get(RedisRuntimeHeaders.CONSUMER_GROUP));
            assertEquals(IllegalStateException.class.getName(), m.getHeaders().get(RedisRuntimeHeaders.ERROR_TYPE));
            String errorMessage = m.getHeaders().get(RedisRuntimeHeaders.ERROR_MESSAGE);
            assertEquals(512, errorMessage.length());
        } finally {
            job.cancel();
        }
    }

    @Test
    void mdcSamplingHonoursZeroAndFractionalRates() {
        boolean mdcUsable = mdcAdapterStoresValues();
        for (double rate : new double[]{0.0d, 0.5d}) {
            RedisRuntimeConfig cfg = baseConfig().mdcEnabled(true).mdcSampleRate(rate).build();
            List<String> mdcJobs = new ArrayList<>();
            RedisStreamExecutionEnvironment env = environment(cfg);
            env.fromMqTopic("t", "g").addSink(v -> mdcJobs.add(org.slf4j.MDC.get("rs.job")));
            RedisJobClient job = env.executeAsync();
            try {
                MessageHandler handler = consumers.get(consumers.size() - 1).handler;
                Message withOriginal = message("a", Map.of(MqHeaders.ORIGINAL_MESSAGE_ID, "orig-1"));
                assertEquals(MessageHandleResult.SUCCESS, handler.handle(withOriginal));
                Message idOnly = message("sample-bucket-1", new HashMap<>());
                assertEquals(MessageHandleResult.SUCCESS, handler.handle(idOnly));
                Message nullId = message(null, new HashMap<>());
                assertEquals(MessageHandleResult.SUCCESS, handler.handle(nullId));
                assertEquals(3, mdcJobs.size());
                if (mdcUsable && rate == 0.0d) {
                    for (String jobName : mdcJobs) {
                        assertNull(jobName, "zero sample rate must skip MDC installation");
                    }
                }
            } finally {
                job.cancel();
            }
        }
    }

    private static boolean mdcAdapterStoresValues() {
        try {
            org.slf4j.MDC.put("rs.probe", "x");
            boolean stored = "x".equals(org.slf4j.MDC.get("rs.probe"));
            org.slf4j.MDC.remove("rs.probe");
            return stored;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void optionsForSubtaskSplitsPartitionsAndCopiesOverrides() {
        RedisRuntimeConfig cfg = baseConfig().pipelineParallelism(2).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g",
                        SubscriptionOptions.builder().batchCount(7).pollTimeoutMs(1234).build())
                .addSink(v -> {
                });
        env.fromMqTopic("t2", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            assertEquals(4, consumers.size());
            SubscriptionOptions o0 = consumers.get(0).options;
            SubscriptionOptions o1 = consumers.get(1).options;
            assertEquals(7, o0.getBatchCount());
            assertEquals(1234L, o0.getPollTimeoutMs());
            assertEquals(2, o0.getPartitionModulo());
            assertEquals(0, o0.getPartitionRemainder());
            assertEquals(2, o1.getPartitionModulo());
            assertEquals(1, o1.getPartitionRemainder());
            assertNull(consumers.get(2).options.getBatchCount());
        } finally {
            job.cancel();
        }
    }

    @Test
    void warnDeferAckConfigurationBranchesAllExecute() {
        assertDoesNotThrow(() -> runAndCancel(baseConfig().deferAckUntilCheckpoint(false).build()));
        assertDoesNotThrow(() -> runAndCancel(baseConfig()
                .deferAckUntilCheckpoint(true)
                .checkpointInterval(Duration.ZERO)
                .mqOptions(MqOptions.builder().claimIdleMs(0).build())
                .build()));
        assertDoesNotThrow(() -> runAndCancel(baseConfig()
                .deferAckUntilCheckpoint(true)
                .checkpointInterval(Duration.ofMillis(200))
                .checkpointDrainTimeout(Duration.ofMillis(500))
                .mqOptions(MqOptions.builder().claimIdleMs(100).build())
                .build()));
        assertDoesNotThrow(() -> runAndCancel(baseConfig()
                .deferAckUntilCheckpoint(true)
                .checkpointInterval(Duration.ofMillis(200))
                .checkpointDrainTimeout(Duration.ofMillis(10))
                .mqOptions(MqOptions.builder().claimIdleMs(100).build())
                .build()));
        assertDoesNotThrow(() -> runAndCancel(baseConfig()
                .deferAckUntilCheckpoint(true)
                .checkpointInterval(Duration.ofMillis(100))
                .checkpointDrainTimeout(Duration.ofMillis(10))
                .mqOptions(MqOptions.builder().claimIdleMs(100_000).build())
                .build()));
        assertDoesNotThrow(() -> runAndCancel(baseConfig()
                .deferAckUntilCheckpoint(true)
                .checkpointInterval(Duration.ZERO)
                .mqOptions(MqOptions.builder().claimIdleMs(50).build())
                .checkpointDrainTimeout(Duration.ofMillis(50))
                .build()));
    }

    @Test
    void ensureConsumerGroupBranchesForFrontierAndScriptResults() {
        when(map.get(any())).thenReturn("5-1");
        assertDoesNotThrow(() -> runAndCancel(baseConfig().restoreConsumerGroupFromCommitFrontier(true).build()));

        when(map.get(any())).thenReturn(null);
        assertDoesNotThrow(() -> runAndCancel(baseConfig().restoreConsumerGroupFromCommitFrontier(true).build()));

        when(map.get(any())).thenReturn("5-1");
        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class))).thenReturn("EXISTS");
        assertDoesNotThrow(() -> runAndCancel(baseConfig().restoreConsumerGroupFromCommitFrontier(true).build()));

        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class))).thenReturn("ERR other");
        assertDoesNotThrow(() -> runAndCancel(baseConfig().restoreConsumerGroupFromCommitFrontier(true).build()));

        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("script down"));
        assertDoesNotThrow(() -> runAndCancel(baseConfig().restoreConsumerGroupFromCommitFrontier(true).build()));

        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class))).thenReturn("OK");
        assertDoesNotThrow(() -> runAndCancel(baseConfig().restoreConsumerGroupFromCommitFrontier(false).build()));
    }

    @Test
    void restoreFromLatestCheckpointFailureIsSwallowed() {
        assertDoesNotThrow(() -> runAndCancel(baseConfig().restoreFromLatestCheckpoint(true).build()));
    }

    @Test
    void checkpointSkipsWhenConsumerIsNotPausable() {
        RedisRuntimeConfig cfg = baseConfig().build();
        when(mqFactory.createConsumer(anyString())).thenAnswer(inv -> new PlainConsumer());
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            assertNull(job.triggerCheckpointNow());
            assertNull(job.getLatestCheckpoint());
            assertEquals(-1L, job.inFlight());
            assertDoesNotThrow(job::pause);
            assertDoesNotThrow(job::resume);
        } finally {
            job.cancel();
        }
    }

    @Test
    void checkpointAbortWhenSinkCheckpointStartFails() {
        AtomicInteger aborts = new AtomicInteger();
        CheckpointAwareSink<String> sink = new CheckpointAwareSink<>() {
            @Override
            public void invoke(String value) {
            }

            @Override
            public void onCheckpointStart(long checkpointId) throws Exception {
                throw new IllegalStateException("start failed");
            }

            @Override
            public void onCheckpointAbort(long checkpointId, Throwable cause) {
                aborts.incrementAndGet();
            }
        };
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopic("t", "g").map(m -> "x").addSink(sink);
        RedisJobClient job = env.executeAsync();
        try {
            assertNull(job.triggerCheckpointNow());
            assertEquals(1, aborts.get());
        } finally {
            job.cancel();
        }
    }

    @Test
    void checkpointDrainTimeoutAndInterruptPathsSettle() throws Exception {
        TestConsumer blocking = null;
        RedisRuntimeConfig cfg = baseConfig().checkpointDrainTimeout(Duration.ofMillis(80)).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            blocking = consumers.get(0);
            blocking.inFlight = 5;
            assertDoesNotThrow(job::triggerCheckpointNow);

            blocking.inFlight = 1;
            blocking.interruptOnInFlight = true;
            assertDoesNotThrow(job::triggerCheckpointNow);
        } finally {
            if (blocking != null) {
                blocking.interruptOnInFlight = false;
            }
            Thread.interrupted();
            job.cancel();
        }
    }

    @Test
    void concurrentTriggerReturnsNullAndResumeFailureIsTolerated() throws Exception {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        TestConsumer consumer = consumers.get(0);
        try {
            consumer.pauseRelease = new CountDownLatch(1);
            consumer.resumeThrows = true;
            CountDownLatch firstDone = new CountDownLatch(1);
            AtomicBoolean firstReturnedCp = new AtomicBoolean();
            Thread first = new Thread(() -> {
                firstReturnedCp.set(job.triggerCheckpointNow() != null);
                firstDone.countDown();
            });
            first.start();
            assertTrue(consumer.pauseEntered.await(5, TimeUnit.SECONDS));
            assertNull(job.triggerCheckpointNow(), "overlapping trigger must be dropped");
            consumer.pauseRelease.countDown();
            assertTrue(firstDone.await(5, TimeUnit.SECONDS));
            first.join();
            assertFalse(firstReturnedCp.get(), "mocked storage cannot persist a checkpoint");
        } finally {
            consumer.release();
            job.cancel();
        }
    }

    @Test
    void jobClientLifecycleEdges() throws Exception {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        TestConsumer consumer = consumers.get(0);
        consumer.pauseThrows = true;
        consumer.resumeThrows = true;
        consumer.inFlightThrows = true;
        assertDoesNotThrow(job::pause);
        assertDoesNotThrow(job::resume);
        assertEquals(0L, job.inFlight());

        consumer.inFlightThrows = false;
        consumer.inFlight = 3;
        assertEquals(3L, job.inFlight());

        Map<String, Object> diag = job.diagnostics();
        assertNotNull(diag.get("jobName"));
        assertEquals(1, diag.get("consumerCount"));
        assertEquals(1, diag.get("runnerCount"));
        assertEquals(3L, ((Number) diag.get("inFlight")).longValue());
        assertNotNull(diag.get("pipelines"));

        assertFalse(job.awaitTermination(null), "job is still running");
        consumer.stopThrows = true;
        consumer.closeThrows = true;
        job.cancel();
        job.cancel();
        assertTrue(job.awaitTermination(Duration.ofSeconds(5)));
        assertNull(job.triggerCheckpointNow());
    }

    @Test
    void executeAsyncStartupFailuresCleanUpAndAllowRetry() {
        RedisRuntimeConfig cfg = baseConfig().build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        TestConsumer failing = new TestConsumer();
        failing.startThrows = true;
        consumers.add(failing);
        when(mqFactory.createConsumer(anyString())).thenReturn(failing);
        RuntimeException e = assertThrows(RuntimeException.class, env::executeAsync);
        assertEquals("start failed", e.getMessage());
        assertTrue(failing.stopped, "startup failure must stop already-created consumers");
        assertTrue(failing.closed);

        // executed flag is reset on failure -> a retry is allowed and succeeds
        TestConsumer ok = new TestConsumer();
        consumers.add(ok);
        when(mqFactory.createConsumer(anyString())).thenReturn(ok);
        RedisJobClient job = env.executeAsync();
        assertNotNull(job);
        job.cancel();
    }

    @Test
    void executeAsyncRejectsSinklessPipelineDefinition() {
        RedisRuntimeConfig cfg = baseConfig().build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.registerPipelineDefinition(new io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineDefinition(
                cfg, redisson, new ObjectMapper(), "t", "g", null, List.of()));
        assertThrows(IllegalStateException.class, env::executeAsync);
    }

    @Test
    void periodicCheckpointsRunInBackground() throws Exception {
        RedisRuntimeConfig cfg = baseConfig().checkpointInterval(Duration.ofMillis(80)).build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            long deadline = System.currentTimeMillis() + 5_000;
            while (consumers.get(0).pauseCount.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertTrue(consumers.get(0).pauseCount.get() >= 1, "periodic checkpoint must pause consumers");
        } finally {
            job.cancel();
        }
    }

    @Test
    void handlerErrorAnnotatesNullHeadersAndNullErrorMessage() {
        RedisRuntimeConfig cfg = baseConfig().build();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").map(m -> {
            throw new IllegalStateException();
        }).addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            Message noHeaders = message("5-2", null);
            assertEquals(MessageHandleResult.RETRY, handler.handle(noHeaders));
            assertEquals(cfg.getJobName(), noHeaders.getHeaders().get(RedisRuntimeHeaders.JOB_NAME));
            assertNull(noHeaders.getHeaders().get(RedisRuntimeHeaders.ERROR_MESSAGE));
        } finally {
            job.cancel();
        }
    }

    private RedisRuntimeConfig.Builder baseConfig() {
        return RedisRuntimeConfig.builder()
                .jobName("mock-rt-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:mock:" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build());
    }

    private RedisStreamExecutionEnvironment environment(RedisRuntimeConfig cfg) {
        return RedisStreamExecutionEnvironment.createForTesting(redisson, cfg, mqFactory, new ObjectMapper());
    }

    private void runAndCancel(RedisRuntimeConfig cfg) {
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t-" + consumers.size(), "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        job.cancel();
    }

    private static Message message(String id, Map<String, String> headers) {
        Message m = new Message();
        m.setId(id);
        m.setTopic("t");
        m.setPayload("p");
        m.setHeaders(headers);
        return m;
    }

    private static class TestConsumer implements MessageConsumer, PausableMessageConsumer {
        volatile MessageHandler handler;
        volatile SubscriptionOptions options;
        volatile boolean startThrows;
        volatile boolean stopThrows;
        volatile boolean closeThrows;
        volatile boolean pauseThrows;
        volatile boolean resumeThrows;
        volatile boolean inFlightThrows;
        volatile boolean interruptOnInFlight;
        volatile boolean paused;
        volatile long inFlight;
        volatile boolean stopped;
        volatile boolean closed;
        final AtomicInteger pauseCount = new AtomicInteger();
        final CountDownLatch pauseEntered = new CountDownLatch(1);
        volatile CountDownLatch pauseRelease;

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
            if (startThrows) {
                throw new RuntimeException("start failed");
            }
        }

        @Override
        public void stop() {
            stopped = true;
            if (stopThrows) {
                throw new RuntimeException("stop failed");
            }
        }

        @Override
        public void close() {
            closed = true;
            if (closeThrows) {
                throw new RuntimeException("close failed");
            }
        }

        @Override
        public boolean isRunning() {
            return !stopped;
        }

        @Override
        public boolean isClosed() {
            return closed;
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
            paused = true;
        }

        @Override
        public void resume() {
            if (resumeThrows) {
                throw new RuntimeException("resume failed");
            }
            paused = false;
        }

        @Override
        public boolean isPaused() {
            return paused;
        }

        @Override
        public long inFlight() {
            if (interruptOnInFlight) {
                Thread.currentThread().interrupt();
            }
            if (inFlightThrows) {
                throw new RuntimeException("inflight failed");
            }
            return inFlight;
        }

        void release() {
            CountDownLatch release = pauseRelease;
            if (release != null) {
                release.countDown();
            }
        }
    }

    private static final class PlainConsumer implements MessageConsumer {
        private MessageHandler handler;

        @Override
        public void subscribe(String topic, MessageHandler handler) {
            this.handler = handler;
        }

        @Override
        public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
            this.handler = handler;
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
    }
}
