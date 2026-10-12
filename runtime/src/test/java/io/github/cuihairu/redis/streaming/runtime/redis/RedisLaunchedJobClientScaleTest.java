package io.github.cuihairu.redis.streaming.runtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.control.ReassignableMessageConsumer;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import io.github.cuihairu.redis.streaming.api.metrics.StreamingMetricsCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@code LaunchedJobClient.scaleParallelism} and the subtask list surgery it
 * drives (repin survivors, remove the tail, add subscribed subtasks on scale-up) — previously
 * exercised only by the Redis integration test. Consumers are fakes recording
 * {@link ReassignableMessageConsumer} re-pins so no broker is needed.
 */
class RedisLaunchedJobClientScaleTest {

    private RedissonClient redisson;
    private RScript script;
    private RMap<Object, Object> map;
    private MessageQueueFactory mqFactory;
    private final List<RecordingConsumer> consumers = new ArrayList<>();
    private StreamingMetricsCollector previousCollector;
    private RedisJobClient job;
    private volatile boolean failNextConsumerCreation;

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
            if (failNextConsumerCreation) {
                failNextConsumerCreation = false;
                throw new IllegalStateException("no consumer this time");
            }
            RecordingConsumer c = new RecordingConsumer();
            consumers.add(c);
            return c;
        });
        previousCollector = RedisRuntimeMetrics.get();
        RedisRuntimeMetrics.setCollector(mock(StreamingMetricsCollector.class));
    }

    @AfterEach
    void tearDown() {
        if (job != null) {
            job.cancel();
            job = null;
        }
        RedisRuntimeMetrics.setCollector(previousCollector);
    }

    private RedisJobClient launch(int parallelism) {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("scale-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("it-scale:" + UUID.randomUUID().toString().substring(0, 6))
                .checkpointKeyPrefix("it-scale:cp:" + UUID.randomUUID().toString().substring(0, 6))
                .pipelineParallelism(parallelism)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.createForTesting(
                redisson, cfg, mqFactory, new ObjectMapper());
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        job = env.executeAsync();
        return job;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private List<Object> subtasks() throws Exception {
        return field(job, "subtasks");
    }

    private RecordingConsumer consumerOfSubtask(int index) throws Exception {
        Object st = subtasks().get(index);
        return (RecordingConsumer) field(st, "consumer");
    }

    @Test
    void scaleParallelismRejectsParallelismBelowOne() {
        RedisJobClient j = launch(1);
        assertThrows(IllegalArgumentException.class, () -> j.scaleParallelism(0));
        assertThrows(IllegalArgumentException.class, () -> j.scaleParallelism(-3));
    }

    @Test
    void scaleParallelismReturnsFalseForCanceledJobs() {
        RedisJobClient j = launch(1);
        j.cancel();
        assertFalse(j.scaleParallelism(2));
    }

    @Test
    void scaleParallelismAtSameParallelismChangesNothing() throws Exception {
        RedisJobClient j = launch(2);
        assertTrue(j.scaleParallelism(2));
        assertEquals(0, consumerOfSubtask(0).reassignments.size());
        assertEquals(2, subtasks().size());
        assertEquals(2, j.diagnostics().get("liveParallelism"));
    }

    @Test
    void scaleParallelismYieldsToInFlightCheckpointAndReportsCanceledWait() throws Exception {
        RedisJobClient j = launch(1);
        AtomicBoolean checkpointing = field(j, "checkpointing");

        // gate busy-held then released by a helper thread: the CAS loop sleeps and retries
        checkpointing.set(true);
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
            }
            checkpointing.set(false);
        });
        releaser.start();
        assertTrue(j.scaleParallelism(1), "released gate must let the scale through");
        releaser.join(5000);

        // interrupt flag set while the gate stays held: the retry sleep aborts into the false arm
        checkpointing.set(true);
        Thread.currentThread().interrupt();
        try {
            assertFalse(j.scaleParallelism(1));
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt status must be restored");
        } finally {
            Thread.interrupted(); // clear for the remaining tests
            checkpointing.set(false);
        }

        // canceled while waiting beats the retry loop immediately
        checkpointing.set(true);
        AtomicBoolean canceled = field(j, "canceled");
        canceled.set(true);
        assertFalse(j.scaleParallelism(1));
        canceled.set(false); // let the teardown cancel() do its full executor cleanup
        checkpointing.set(false);
    }

    @Test
    void scaleDownRepinsSurvivorsAndStopsRemovedSubtasks() throws Exception {
        RedisJobClient j = launch(2);
        RecordingConsumer survivor = consumerOfSubtask(0);
        RecordingConsumer removed = consumerOfSubtask(1);

        assertTrue(j.scaleParallelism(1));

        assertEquals(List.of(List.of("t", 1, 0)), survivor.reassignments,
                "survivor repinned to the new parallelism");
        assertTrue(removed.stopped, "removed consumer stopped so leases hand over promptly");
        assertTrue(removed.closed);
        assertFalse(survivor.stopped, "survivor keeps running");

        Map<String, Object> diag = j.diagnostics();
        assertEquals(1, diag.get("liveParallelism"));
        assertEquals(1, diag.get("consumerCount"));
        assertEquals(1, diag.get("runnerCount"));
    }

    @Test
    void scaleUpRepinsExistingAndAddsSubscribedSubtasks() throws Exception {
        RedisJobClient j = launch(1);

        assertTrue(j.scaleParallelism(2));

        assertEquals(List.of(List.of("t", 2, 0)), consumerOfSubtask(0).reassignments);
        RecordingConsumer added = consumerOfSubtask(1);
        assertEquals("t", added.topic);
        assertEquals("g", added.group);
        assertEquals(2, added.options.getPartitionModulo());
        assertEquals(1, added.options.getPartitionRemainder());
        assertTrue(added.started, "new subtask consumer must be started");

        Map<String, Object> diag = j.diagnostics();
        assertEquals(2, diag.get("liveParallelism"));
        assertEquals(2, diag.get("consumerCount"));
    }

    @Test
    void scaleUpFailureReleasesTheScaleGateAndKeepsTheJobIntact() throws Exception {
        RedisJobClient j = launch(1);
        failNextConsumerCreation = true;

        assertFalse(j.scaleParallelism(2));
        assertEquals(1, subtasks().size(), "failed scale must not mutate the subtask list");
        assertEquals(Boolean.FALSE, j.diagnostics().get("checkpointing"), "scale gate must be released");
        assertTrue(j.scaleParallelism(2), "job stays scalable after the failed attempt");
    }

    private static final class RecordingConsumer implements MessageConsumer, ReassignableMessageConsumer {
        final List<Object> reassignments = new ArrayList<>();
        volatile String topic;
        volatile String group;
        volatile SubscriptionOptions options;
        volatile boolean started;
        volatile boolean stopped;
        volatile boolean closed;

        @Override
        public void subscribe(String topic, MessageHandler handler) {
            this.topic = topic;
        }

        @Override
        public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
            this.topic = topic;
            this.group = consumerGroup;
        }

        @Override
        public void subscribe(String topic, String consumerGroup, MessageHandler handler, SubscriptionOptions options) {
            this.topic = topic;
            this.group = consumerGroup;
            this.options = options;
        }

        @Override
        public void unsubscribe(String topic) {
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isRunning() {
            return started && !stopped;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public boolean updatePartitionAssignment(String topic, int partitionModulo, int partitionRemainder) {
            reassignments.add(List.of(topic, partitionModulo, partitionRemainder));
            return true;
        }
    }
}
