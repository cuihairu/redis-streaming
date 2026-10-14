package io.github.cuihairu.redis.streaming.runtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.control.PausableMessageConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wiring of the cross-instance job control gate into the control operations: when the gate is
 * enabled, a checkpoint or scale that cannot win the job-global lease is refused <em>before</em>
 * any stop-the-world work (no consumer pause), a won lease lets the checkpoint run untouched,
 * and a disabled gate never touches the lease keys at all — the single-instance behavior this
 * feature must not change.
 */
class RedisStreamExecutionEnvironmentControlGateTest {

    private RedissonClient redisson;
    private MessageQueueFactory mqFactory;
    private final List<RecordingConsumer> consumers = new ArrayList<>();
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final List<String> evalKeys = Collections.synchronizedList(new ArrayList<>());
    /** Simulates the job-global lease: while set, another instance owns ctl:excl/ctl:silence. */
    private final AtomicBoolean leaseHeldByAnother = new AtomicBoolean(false);

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RMap<Object, Object> map = mock(RMap.class);
        when(redisson.getScript(any(Codec.class))).thenReturn(script);
        when(redisson.getMap(anyString())).thenReturn((RMap) map);
        when(redisson.getMap(anyString(), any(Codec.class))).thenReturn((RMap) map);
        RBucket<Object> bucket = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn((RBucket) bucket);
        // A lone any() in the varargs position matches ANY arity, and Mockito lets the
        // last-registered matching stub win — so the wide stub must come first, the exact
        // two-value one second.
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                        anyList(), any()))
                .thenAnswer(this::evalAnswer);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                        anyList(), any(), any()))
                .thenAnswer(this::evalAnswer);
        mqFactory = mock(MessageQueueFactory.class);
        when(mqFactory.createConsumer(anyString())).thenAnswer(inv -> {
            RecordingConsumer c = new RecordingConsumer(events);
            consumers.add(c);
            return c;
        });
    }

    @SuppressWarnings("rawtypes")
    private Object evalAnswer(InvocationOnMock inv) {
        Object[] args = inv.getArguments();
        if (args.length > 3) {
            List keys = (List) args[3];
            if (!keys.isEmpty()) {
                evalKeys.add(String.valueOf(keys.get(0)));
            }
        }
        String lua = String.valueOf(args[1]);
        // The acquire script renews in place (its elseif branch mentions PEXPIRE), so the grant is
        // only the script that sets the value with 'PX'
        boolean acquire = lua.contains("'SET'");
        return acquire && leaseHeldByAnother.get() ? 0L : 1L;
    }

    private RedisJobClient launch(boolean gateEnabled) {
        RedisRuntimeConfig.Builder cfg = RedisRuntimeConfig.builder()
                .jobName("ctl-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("ctl-state:" + UUID.randomUUID().toString().substring(0, 6))
                .checkpointKeyPrefix("ctl-cp:" + UUID.randomUUID().toString().substring(0, 6))
                .checkpointsToKeep(2)
                .checkpointDrainTimeout(Duration.ofMillis(200))
                .restoreConsumerGroupFromCommitFrontier(false)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build());
        if (gateEnabled) {
            cfg.jobControlGateEnabled(true);
        }
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.createForTesting(
                redisson, cfg.build(), mqFactory, new ObjectMapper());
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        return env.executeAsync();
    }

    @Test
    void contendedControlLeaseRefusesCheckpointAndScaleBeforeAnyStopTheWorldWork() {
        leaseHeldByAnother.set(true);
        RedisJobClient job = launch(true);
        try {
            assertNull(job.triggerCheckpointNow(),
                    "a checkpoint that cannot win the job-global lease must be refused");
            assertFalse(job.scaleParallelism(1),
                    "a scale that cannot win the job-global lease must be refused");
            assertFalse(events.contains("pause"),
                    "the refusal must happen before any consumer is paused (events=" + events + ")");
        } finally {
            job.cancel();
        }
    }

    @Test
    void wonControlLeaseLetsTheCheckpointRunAndReleasesIt() {
        RedisJobClient job = launch(true);
        try {
            assertNotNull(job.triggerCheckpointNow());
            assertTrue(events.contains("pause"), "the granted checkpoint must run its flow");
            assertTrue(events.contains("resume"));
            assertTrue(job.scaleParallelism(1), "an uncontended no-op scale must succeed");
            assertTrue(evalKeys.stream().anyMatch(k -> k.endsWith(":ctl:excl")),
                    "the gate must have touched the exclusive lease key, keys=" + evalKeys);
            assertTrue(evalKeys.stream().anyMatch(k -> k.endsWith(":ctl:silence")),
                    "the gate must have touched the silence lease key, keys=" + evalKeys);
        } finally {
            job.cancel();
        }
    }

    @Test
    void disabledGateNeverTouchesTheLeaseKeys() {
        RedisJobClient job = launch(false);
        try {
            assertNotNull(job.triggerCheckpointNow(),
                    "a disabled gate must leave the single-instance behavior unchanged");
            assertTrue(job.scaleParallelism(1));
            assertTrue(evalKeys.stream().noneMatch(k -> k.contains(":ctl:")),
                    "no control lease key may be touched while the gate is disabled, keys=" + evalKeys);
        } finally {
            job.cancel();
        }
    }

    private static final class RecordingConsumer implements MessageConsumer, PausableMessageConsumer {

        private final List<String> events;

        private RecordingConsumer(List<String> events) {
            this.events = events;
        }

        @Override
        public void subscribe(String topic, MessageHandler handler) {
        }

        @Override
        public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
        }

        @Override
        public void subscribe(String topic, String consumerGroup, MessageHandler handler, SubscriptionOptions options) {
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
            events.add("pause");
        }

        @Override
        public void resume() {
            events.add("resume");
        }

        @Override
        public long inFlight() {
            return 0;
        }

        @Override
        public boolean isPaused() {
            return false;
        }
    }
}
