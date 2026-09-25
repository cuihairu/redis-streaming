package io.github.cuihairu.redis.streaming.runtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.control.PausableMessageConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Residual branches of {@code RedisStreamExecutionEnvironment#triggerCheckpointInternal}:
 * null/empty consumer lists, {@code checkpointDrainTimeout == null}, zero drain timeout with
 * in-flight drain, and the defensive {@code instanceof PausableMessageConsumer} arm in the
 * finally resume loop (a consumer list that mutates mid-checkpoint is simulated by a list whose
 * iterators hand out non-pausable consumers after the initial guards have run).
 */
class RedisStreamExecutionEnvironmentCheckpointGapTest {

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
        when(redisson.getScript(any(Codec.class))).thenReturn(script);
        when(redisson.getMap(anyString())).thenReturn(map);
        when(redisson.getMap(anyString(), any(Codec.class))).thenReturn(map);
        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class))).thenReturn("OK");
        org.redisson.api.RBucket<Object> bucket = mock(org.redisson.api.RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn((org.redisson.api.RBucket) bucket);
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

    private RedisRuntimeConfig.Builder baseConfig() {
        return RedisRuntimeConfig.builder()
                .jobName("gap-cp-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("it-gap-cp:" + UUID.randomUUID().toString().substring(0, 6))
                .checkpointKeyPrefix("it-gap-cp:cp:" + UUID.randomUUID().toString().substring(0, 6))
                .restoreConsumerGroupFromCommitFrontier(false)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build());
    }

    private RedisJobClient launch(RedisRuntimeConfig cfg) {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.createForTesting(
                redisson, cfg, mqFactory, new ObjectMapper());
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        return env.executeAsync();
    }

    private static MessageConsumer plain = new PlainConsumer();

    @Test
    void triggerCheckpointReturnsNullForNullConsumerList() throws Exception {
        RedisJobClient job = launch(baseConfig().build());
        try {
            Field outer = job.getClass().getDeclaredField("this$0");
            outer.setAccessible(true);
            Object env = outer.get(job);
            Method trigger = findTriggerMethod();
            Object result = trigger.invoke(env, null, List.of(), null, List.of(), null);
            assertNull(result, "a missing consumer list must skip the checkpoint");
        } finally {
            job.cancel();
        }
    }

    private static Method findTriggerMethod() {
        for (Method m : RedisStreamExecutionEnvironment.class.getDeclaredMethods()) {
            if (m.getName().equals("triggerCheckpointInternal") && m.getParameterCount() == 5) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new IllegalStateException("triggerCheckpointInternal not found");
    }

    @Test
    void emptyConsumerListSkipsCheckpoint() throws Exception {
        RedisJobClient job = launch(baseConfig().build());
        try {
            Field consumersField = job.getClass().getDeclaredField("consumers");
            consumersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<MessageConsumer> original = (List<MessageConsumer>) consumersField.get(job);
            consumersField.set(job, new ArrayList<>());
            assertNull(job.triggerCheckpointNow(), "no consumers means nothing to checkpoint");
            consumersField.set(job, original);
        } finally {
            job.cancel();
        }
    }

    @Test
    void nullDrainTimeoutSkipsDeadlineCheck() {
        RedisRuntimeConfig real = baseConfig().build();
        RedisRuntimeConfig cfg = mock(RedisRuntimeConfig.class, delegatesTo(real));
        when(cfg.getCheckpointDrainTimeout()).thenReturn(null);
        RedisJobClient job = launch(cfg);
        try {
            assertNotNull(job.triggerCheckpointNow(), "null drain timeout still checkpoints");
        } finally {
            job.cancel();
        }
    }

    @Test
    void zeroDrainTimeoutDrainsBeforeProceeding() {
        RedisJobClient job = launch(baseConfig().checkpointDrainTimeout(Duration.ZERO).build());
        TestConsumer c = consumers.get(0);
        try {
            AtomicInteger reads = new AtomicInteger();
            c.inFlightSupplier = () -> reads.incrementAndGet() == 1 ? 1L : 0L;
            assertNotNull(job.triggerCheckpointNow(), "zero drain timeout still checkpoints");
        } finally {
            c.inFlightSupplier = null;
            job.cancel();
        }
    }

    @Test
    void finallyLoopSkipsConsumerThatBecameNonPausable() throws Exception {
        RedisJobClient job = launch(baseConfig().build());
        TestConsumer pausable = consumers.get(0);
        try {
            Field consumersField = job.getClass().getDeclaredField("consumers");
            consumersField.setAccessible(true);
            consumersField.set(job, new FlipFlopList(pausable, plain));
            assertNotNull(job.triggerCheckpointNow(), "checkpoint survives a mutated consumer list");
            assertEquals(0, pausable.resumeCalls,
                    "the resume loop only sees the swapped-in non-pausable consumer and skips it");
        } finally {
            job.cancel();
        }
    }

    /**
     * Hands out the pausable consumer for the guard/pause/drain loops and a non-pausable
     * consumer afterwards, simulating a consumer list that mutates while a checkpoint is running.
     */
    private static final class FlipFlopList extends ArrayList<MessageConsumer> {
        private final AtomicInteger iteratorCalls = new AtomicInteger();

        FlipFlopList(MessageConsumer pausable, MessageConsumer late) {
            super(List.of(pausable, late));
        }

        @Override
        public Iterator<MessageConsumer> iterator() {
            if (iteratorCalls.incrementAndGet() <= 3) {
                return List.of(get(0)).iterator();
            }
            return List.of(get(1)).iterator();
        }
    }

    private static final class PlainConsumer implements MessageConsumer {
        int resumeCalls;

        @Override
        public void subscribe(String topic, MessageHandler handler) {
        }

        @Override
        public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
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

    private static final class TestConsumer implements MessageConsumer, PausableMessageConsumer {
        volatile MessageHandler handler;
        volatile java.util.function.LongSupplier inFlightSupplier;
        int resumeCalls;

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
        }

        @Override
        public void resume() {
            resumeCalls++;
        }

        @Override
        public boolean isPaused() {
            return false;
        }

        @Override
        public long inFlight() {
            return inFlightSupplier == null ? 0L : inFlightSupplier.getAsLong();
        }

        void release() {
        }
    }
}
