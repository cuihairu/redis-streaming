package io.github.cuihairu.redis.streaming.runtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3 leg 1: engine-level dual-source pipelines (design plan A) — one pipeline, two MQ
 * sources, one operator chain, one consumer per subtask.
 *
 * <p>The dual-source form is what lets a stream-stream join run directly on two producer topics
 * instead of relaying both sides through a join topic. What has to hold for that to be correct:
 *
 * <ul>
 * <li>the single consumer of a subtask is subscribed to <em>both</em> sources before it starts,
 * so the rebalance sees every subscription at once;</li>
 * <li>each source keeps its own consumer group, offsets, defer-ack scope and partition pinning —
 * the arriving record's topic decides which one;</li>
 * <li>the duplicate (topic, group) guard covers the peer source too, not just the primary;</li>
 * <li>both sources are checkpointed independently (two offset entries, one job);</li>
 * <li>co-location is a start-time precondition: mismatched partition counts would silently keep
 * the two sides of a join key in different keyed-state partitions, so the start fails instead.</li>
 * </ul>
 *
 * <p>Driven with mocked Redis and MQ plumbing — no broker needed. The watermark half of Phase 3
 * lives in {@code RedisPipelineRunnerDualSourceWatermarkTest}.
 */
class RedisDualSourcePipelineTest {

    private static final String LEFT = "dual-left";
    private static final String LEFT_GROUP = "dual-left-g";
    private static final String RIGHT = "dual-right";
    private static final String RIGHT_GROUP = "dual-right-g";

    private RedissonClient redisson;
    private RScript script;
    private MessageQueueFactory mqFactory;
    private final List<RecordingConsumer> consumers = new ArrayList<>();
    /** topic name fragment -> partitionCount the topic meta hash reports */
    private final Map<String, String> partitionCounts = new LinkedHashMap<>();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        when(redisson.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class))).thenReturn("OK");
        when(redisson.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            RMap<String, String> meta = mock(RMap.class);
            for (Map.Entry<String, String> e : partitionCounts.entrySet()) {
                if (name.contains(e.getKey())) {
                    when(meta.get("partitionCount")).thenReturn(e.getValue());
                }
            }
            return meta;
        });
        mqFactory = mock(MessageQueueFactory.class);
        when(mqFactory.createConsumer(anyString())).thenAnswer(inv -> {
            RecordingConsumer c = new RecordingConsumer();
            consumers.add(c);
            return c;
        });
    }

    // ---------- the dual subscribe ----------

    @Test
    void oneConsumerSubscribesToBothSourcesBeforeItStarts() {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(LEFT, LEFT_GROUP, RIGHT, RIGHT_GROUP).addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            assertEquals(1, consumers.size(), "one subtask means one consumer for both sources");
            RecordingConsumer c = consumers.get(0);
            assertEquals(List.of("subscribe:" + LEFT, "subscribe:" + RIGHT, "start"), c.events,
                    "both sources must be subscribed before start() so the rebalance sees them together");
            assertEquals(LEFT_GROUP, c.sub(LEFT).consumerGroup);
            assertEquals(RIGHT_GROUP, c.sub(RIGHT).consumerGroup);
            // the same handler instance serves both sources, resolving the source per record
            assertNotNull(c.sub(LEFT).handler);
            assertEquals(c.sub(LEFT).handler, c.sub(RIGHT).handler);
        } finally {
            job.cancel();
        }
    }

    @Test
    void recordsFromEitherSourceReachTheSameChain() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(LEFT, LEFT_GROUP, RIGHT, RIGHT_GROUP)
                .map(v -> {
                    seen.add("map");
                    return v;
                })
                .addSink(v -> seen.add("sink:" + ((Message) v).getTopic() + "/" + ((Message) v).getId()));
        RedisJobClient job = env.executeAsync();
        try {
            RecordingConsumer c = consumers.get(0);
            seen.clear();
            assertEquals(MessageHandleResult.SUCCESS, c.sub(RIGHT).handler.handle(message("r-1", RIGHT)));
            assertEquals(List.of("map", "sink:" + RIGHT + "/r-1"), seen, "the peer source runs the same chain");
            seen.clear();
            assertEquals(MessageHandleResult.SUCCESS, c.sub(LEFT).handler.handle(message("l-1", LEFT)));
            assertEquals(List.of("map", "sink:" + LEFT + "/l-1"), seen);
        } finally {
            job.cancel();
        }
    }

    @Test
    void perSourceSubscriptionOptionsSurviveTheSubtaskFanOut() {
        SubscriptionOptions fast = SubscriptionOptions.builder().batchCount(64).build();
        RedisStreamExecutionEnvironment env = environment(baseConfig()
                .pipelineParallelism(2).build());
        env.fromMqTopics(LEFT, LEFT_GROUP, fast, RIGHT, RIGHT_GROUP, null)
                .addSink(v -> {
                });
        RedisJobClient job = env.executeAsync();
        try {
            RecordingConsumer c = consumers.get(0);
            assertEquals(64, c.sub(LEFT).options.getBatchCount(), "the left override is carried through");
            // null means "the MQ defaults", so only the pin is added for that source
            assertNull(c.sub(RIGHT).options.getBatchCount());
            assertEquals(2, c.sub(RIGHT).options.getPartitionModulo());
        } finally {
            job.cancel();
        }
    }

    @Test
    void everySourceIsPartitionPinnedForParallelSubtasks() {
        RedisStreamExecutionEnvironment env = environment(baseConfig()
                .pipelineParallelism(2).build());
        env.fromMqTopics(LEFT, LEFT_GROUP, RIGHT, RIGHT_GROUP).addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            assertEquals(2, consumers.size(), "parallelism 2 gives one consumer per source pair");
            for (int subtask = 0; subtask < 2; subtask++) {
                RecordingConsumer c = consumers.get(subtask);
                assertEquals(2, c.subscriptions().size());
                for (Sub s : c.subscriptions()) {
                    assertEquals(2, s.options.getPartitionModulo(),
                            "source " + s.topic + " of subtask " + subtask + " must share the pin");
                    assertEquals(subtask, s.options.getPartitionRemainder());
                }
            }
        } finally {
            job.cancel();
        }
    }

    // ---------- both sources are checkpointed ----------

    @Test
    void bothSourcesAppearAsCheckpointPipelines() {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(LEFT, LEFT_GROUP, RIGHT, RIGHT_GROUP).addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pipelines = (List<Map<String, Object>>) job.diagnostics().get("pipelines");
            assertNotNull(pipelines);
            List<String> pairs = new ArrayList<>();
            for (Map<String, Object> p : pipelines) {
                pairs.add(p.get("topic") + "/" + p.get("consumerGroup"));
            }
            assertEquals(List.of(LEFT + "/" + LEFT_GROUP, RIGHT + "/" + RIGHT_GROUP), pairs,
                    "offsets are tracked per source, so both sides get their own checkpoint entry");
            assertEquals(1, job.diagnostics().get("liveParallelism"),
                    "two checkpoint keys, but still one pipeline unit");
        } finally {
            job.cancel();
        }
    }

    // ---------- guards ----------

    @Test
    void theDuplicateGroupGuardCoversThePeerSourceToo() {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(LEFT, LEFT_GROUP, RIGHT, RIGHT_GROUP).addSink(v -> {
        });
        // the peer source is a claimed (topic, group) just like the primary one
        assertThrows(IllegalStateException.class, () -> env.fromMqTopic(RIGHT, RIGHT_GROUP).addSink(v -> {
        }));
    }

    @Test
    void theSameDualSourceCannotBeRegisteredTwice() {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(LEFT, LEFT_GROUP, RIGHT, RIGHT_GROUP).addSink(v -> {
        });
        assertThrows(IllegalStateException.class,
                () -> env.fromMqTopics(LEFT, LEFT_GROUP, RIGHT, RIGHT_GROUP).addSink(v -> {
                }));
    }

    @Test
    void sourcesWithDifferentPartitionCountsFailTheStart() {
        partitionCounts.put(LEFT, "2");
        partitionCounts.put(RIGHT, "3");

        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(LEFT, LEFT_GROUP, RIGHT, RIGHT_GROUP).addSink(v -> {
        });

        IllegalStateException e = assertThrows(IllegalStateException.class, env::executeAsync);
        assertTrue(e.getMessage().contains("same partition count"), e.getMessage());
    }

    @Test
    void matchingPartitionCountsStartCleanly() {
        partitionCounts.put(LEFT, "2");
        partitionCounts.put(RIGHT, "2");

        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(LEFT, LEFT_GROUP, RIGHT, RIGHT_GROUP).addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            assertEquals(1, consumers.size());
        } finally {
            job.cancel();
        }
    }

    @Test
    void aSingleSourcePipelineIsNeverPartitionChecked() {
        partitionCounts.put(LEFT, "2");
        partitionCounts.put(RIGHT, "3");

        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopic(LEFT, LEFT_GROUP).addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            assertEquals(1, consumers.size());
        } finally {
            job.cancel();
        }
    }

    @Test
    void dualSourceFactoryValidatesItsArguments() {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());

        assertThrows(NullPointerException.class, () -> env.fromMqTopics(null, LEFT_GROUP, RIGHT, RIGHT_GROUP));
        assertThrows(NullPointerException.class, () -> env.fromMqTopics(LEFT, null, RIGHT, RIGHT_GROUP));
        assertThrows(NullPointerException.class, () -> env.fromMqTopics(LEFT, LEFT_GROUP, null, RIGHT_GROUP));
        assertThrows(NullPointerException.class, () -> env.fromMqTopics(LEFT, LEFT_GROUP, RIGHT, null));

        assertNotNull(env.fromMqTopicsWithId("dual-1", LEFT, LEFT_GROUP, null, RIGHT, RIGHT_GROUP, null));
        assertThrows(IllegalStateException.class, () -> env.fromMqTopicsWithId(
                "dual-1", LEFT, LEFT_GROUP, null, RIGHT, RIGHT_GROUP, null));
        assertThrows(IllegalArgumentException.class, () -> env.fromMqTopicsWithId(
                "bad id!", LEFT, LEFT_GROUP, null, RIGHT, RIGHT_GROUP, null));
    }

    @Test
    void theSameTopicOnBothSidesIsAllowedWithDistinctGroups() {
        // a self-join: one topic, two consumer groups, so each group sees every record
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(LEFT, LEFT_GROUP, LEFT, RIGHT_GROUP).addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        try {
            // one subscription per (topic, group); the consumer holds both groups on the one topic
            assertEquals(List.of("subscribe:" + LEFT, "subscribe:" + LEFT, "start"), consumers.get(0).events);
            assertEquals(List.of(LEFT_GROUP, RIGHT_GROUP), groupsOf(consumers.get(0), LEFT));
        } finally {
            job.cancel();
        }
    }

    @Test
    void aDualSourcePipelineWithoutASinkStillRefusesToStart() {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(LEFT, LEFT_GROUP, RIGHT, RIGHT_GROUP);
        assertThrows(IllegalStateException.class, env::executeAsync);
    }

    // ---------- helpers ----------

    private static List<String> groupsOf(RecordingConsumer c, String topic) {
        List<String> groups = new ArrayList<>();
        for (Sub s : c.subscriptions()) {
            if (s.topic.equals(topic)) {
                groups.add(s.consumerGroup);
            }
        }
        return groups;
    }

    private RedisRuntimeConfig.Builder baseConfig() {
        return RedisRuntimeConfig.builder()
                .jobName("dual-rt-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:dual:" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build());
    }

    private RedisStreamExecutionEnvironment environment(RedisRuntimeConfig cfg) {
        return RedisStreamExecutionEnvironment.createForTesting(redisson, cfg, mqFactory, new ObjectMapper());
    }

    private static Message message(String id, String topic) {
        Message m = new Message();
        m.setId(id);
        m.setTopic(topic);
        m.setPayload("p");
        return m;
    }

    private static final class Sub {
        final String topic;
        final String consumerGroup;
        final MessageHandler handler;
        final SubscriptionOptions options;

        Sub(String topic, String consumerGroup, MessageHandler handler, SubscriptionOptions options) {
            this.topic = topic;
            this.consumerGroup = consumerGroup;
            this.handler = handler;
            this.options = options;
        }
    }

    /** Consumer that records its subscriptions (in order) and its start call. */
    private static final class RecordingConsumer implements MessageConsumer {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<Sub> subs = Collections.synchronizedList(new ArrayList<>());
        volatile boolean stopped;
        volatile boolean closed;

        List<Sub> subscriptions() {
            synchronized (subs) {
                return new ArrayList<>(subs);
            }
        }

        /** First subscription on {@code topic} (the pair order is source 0 then source 1). */
        Sub sub(String topic) {
            for (Sub s : subscriptions()) {
                if (s.topic.equals(topic)) {
                    return s;
                }
            }
            throw new AssertionError("no subscription on topic " + topic + ": " + events);
        }

        @Override
        public void subscribe(String topic, MessageHandler handler) {
            subscribe(topic, null, handler, null);
        }

        @Override
        public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
            subscribe(topic, consumerGroup, handler, null);
        }

        @Override
        public void subscribe(String topic, String consumerGroup, MessageHandler handler,
                               SubscriptionOptions opts) {
            events.add("subscribe:" + topic);
            subs.add(new Sub(topic, consumerGroup, handler, opts));
        }

        @Override
        public void unsubscribe(String topic) {
        }

        @Override
        public void start() {
            events.add("start");
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
            return !stopped;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }
}