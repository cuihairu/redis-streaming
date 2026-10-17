package io.github.cuihairu.redis.streaming.runtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisRuntimeCheckpointManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * Phase 3 leg 1: the dual-source (plan A) pipeline topology, driven with mocked Redis and MQ
 * plumbing. One {@code fromMqTopics(t1, g1, t2, g2)} pipeline must read both topics through a
 * single consumer, one operator chain and one handler, while everything that used to be
 * single-source — offsets, group bootstrap, partition pinning — must be applied <em>per source</em>
 * rather than silently only for the first topic.
 *
 * <p>The single-source tests in {@code RedisStreamExecutionEnvironmentMockTest} pin the unchanged
 * half; the last test here pins that a single-source pipeline still contributes exactly one
 * checkpoint key, which is what the dual-source per-source loop must not have broken.
 */
class RedisStreamExecutionEnvironmentDualSourceMockTest {

    private static final String TOPIC_L = "ds-tL";
    private static final String TOPIC_R = "ds-tR";

    private RedissonClient redisson;
    private RScript script;
    private RMap<Object, Object> map;
    private MessageQueueFactory mqFactory;
    private final List<RecordingConsumer> consumers = new ArrayList<>();
    /** topic -> declared partition count, served through the topic-meta hash mock. */
    private final Map<String, Integer> partitionCounts = new HashMap<>();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        map = mock(RMap.class);
        RStreamPlaceholder holder = new RStreamPlaceholder();
        when(redisson.getScript(any(Codec.class))).thenReturn(script);
        when(redisson.getMap(anyString())).thenReturn(map);
        // topic-meta reads are the only partition-count source: serve a per-topic hash and let
        // everything else fall back to the shared no-op map (as the sibling mock tests do)
        when(redisson.getMap(anyString(), any(Codec.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            for (Map.Entry<String, Integer> e : partitionCounts.entrySet()) {
                if (key.contains(e.getKey())) {
                    return metaMap(e.getValue());
                }
            }
            return map;
        });
        when(redisson.<Object, Object>getStream(anyString(), any())).thenReturn(holder.stream);
        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class))).thenReturn("OK");
        org.redisson.api.RBucket<Object> bucket = mock(org.redisson.api.RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn((org.redisson.api.RBucket) bucket);
        mqFactory = mock(MessageQueueFactory.class);
        when(mqFactory.createConsumer(anyString())).thenAnswer(inv -> {
            RecordingConsumer c = new RecordingConsumer();
            consumers.add(c);
            return c;
        });
    }

    @AfterEach
    void tearDown() {
        consumers.forEach(RecordingConsumer::release);
    }

    /** Holder so the mock setup reads cleanly (an RStream stub is needed by the start path). */
    private static final class RStreamPlaceholder {
        @SuppressWarnings("rawtypes")
        final org.redisson.api.RStream stream = mock(org.redisson.api.RStream.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RMap<Object, Object> metaMap(int partitionCount) {
        RMap<Object, Object> meta = mock(RMap.class);
        when(meta.get("partitionCount")).thenReturn(Integer.toString(partitionCount));
        return meta;
    }

    private RedisRuntimeConfig.Builder baseConfig() {
        return RedisRuntimeConfig.builder()
                .jobName("ds-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:ds:" + UUID.randomUUID().toString().substring(0, 6))
                .checkpointKeyPrefix("ds:cp:" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build());
    }

    private RedisStreamExecutionEnvironment environment(RedisRuntimeConfig cfg) {
        return RedisStreamExecutionEnvironment.createForTesting(redisson, cfg, mqFactory, new ObjectMapper());
    }

    // ---------- one consumer, both topics, one handler ----------

    @Test
    void oneConsumerSubscribesToBothTopicsBeforeItStarts() {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        List<Message> out = new ArrayList<>();
        env.fromMqTopics(TOPIC_L, "groupL", TOPIC_R, "groupR").addSink(out::add);

        RedisJobClient job = env.executeAsync();
        try {
            assertEquals(1, consumers.size(), "one chain means one consumer, not two");
            RecordingConsumer consumer = consumers.get(0);
            assertEquals(List.of(TOPIC_L, TOPIC_R), consumer.subscribedTopics());
            assertEquals(List.of("groupL", "groupR"), consumer.subscribedGroups());
            assertSame(consumer.handler, consumer.handler,
                    "both subscriptions share one handler, i.e. one operator chain");
            assertEquals(1, consumer.starts, "start() runs once, after every subscription is in place");
            assertTrue(consumer.subscribedBeforeStart, "subscribing after start() would miss the initial rebalance");
        } finally {
            job.cancel();
        }
    }

    @Test
    void recordsFromBothSourcesReachTheSameOperatorChain() throws Exception {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        List<Message> out = new ArrayList<>();
        env.fromMqTopics(TOPIC_L, "groupL", TOPIC_R, "groupR").addSink(out::add);

        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            assertEquals(io.github.cuihairu.redis.streaming.mq.MessageHandleResult.SUCCESS,
                    handler.handle(message(TOPIC_L, "1-1")));
            assertEquals(io.github.cuihairu.redis.streaming.mq.MessageHandleResult.SUCCESS,
                    handler.handle(message(TOPIC_R, "1-0")));
            assertEquals(2, out.size(), "both sides of the join flow through the one chain");

            // a record whose topic is not ours cannot crash the handler: it resolves to the primary
            assertEquals(io.github.cuihairu.redis.streaming.mq.MessageHandleResult.SUCCESS,
                    handler.handle(message("someOtherTopic", "1-0")));
            assertEquals(3, out.size());
        } finally {
            job.cancel();
        }
    }

    @Test
    void perSourceSubscriptionOptionsAreAppliedIndependently() {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(TOPIC_L, "groupL", SubscriptionOptions.builder().batchCount(7).pollTimeoutMs(1234).build(),
                        TOPIC_R, "groupR", SubscriptionOptions.builder().batchCount(9).build())
                .addSink(v -> {
                });

        RedisJobClient job = env.executeAsync();
        try {
            RecordingConsumer consumer = consumers.get(0);
            assertEquals(7, consumer.optionsAt(0).getBatchCount());
            assertEquals(1234L, consumer.optionsAt(0).getPollTimeoutMs());
            assertEquals(9, consumer.optionsAt(1).getBatchCount());
            assertNull(consumer.optionsAt(1).getPollTimeoutMs(),
                    "source 2 must not inherit source 1's overrides");
        } finally {
            job.cancel();
        }
    }

    @Test
    void everySourceIsPinnedToTheSamePartitionSliceOfTheSubtask() {
        RedisStreamExecutionEnvironment env = environment(baseConfig().pipelineParallelism(2).build());
        env.fromMqTopics(TOPIC_L, "groupL", TOPIC_R, "groupR").addSink(v -> {
        });
        env.fromMqTopics("ds-tL2", "groupL2", "ds-tR2", "groupR2").addSink(v -> {
        });

        RedisJobClient job = env.executeAsync();
        try {
            assertEquals(4, consumers.size(), "2 pipelines x 2 subtasks");
            for (RecordingConsumer consumer : consumers) {
                SubscriptionOptions first = consumer.optionsAt(0);
                SubscriptionOptions second = consumer.optionsAt(1);
                assertEquals(2, first.getPartitionModulo());
                assertEquals(2, second.getPartitionModulo());
                assertEquals(first.getPartitionRemainder(), second.getPartitionRemainder(),
                        "a subtask must own the same slice in both sources or a join key never meets");
            }
        } finally {
            job.cancel();
        }
    }

    // ---------- per-source identity everywhere it matters ----------

    @Test
    void theDuplicateGroupGuardClaimsEverySourceOfThePipeline() {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(TOPIC_L, "groupL", TOPIC_R, "groupR").addSink(v -> {
        });

        // the second pipeline collides on the FIRST source
        IllegalStateException first = assertThrows(IllegalStateException.class,
                () -> env.fromMqTopics(TOPIC_L, "groupL", "ds-other", "groupOther").addSink(v -> {
                }));
        assertTrue(first.getMessage().contains(TOPIC_L), first.getMessage());

        // ...and on the SECOND source too: the guard must not stop at topic 0
        IllegalStateException second = assertThrows(IllegalStateException.class,
                () -> env.fromMqTopics("ds-other2", "groupOther2", TOPIC_R, "groupR").addSink(v -> {
                }));
        assertTrue(second.getMessage().contains(TOPIC_R), second.getMessage());
        assertTrue(second.getMessage().contains("groupR"), second.getMessage());
    }

    @Test
    void bothSourcesContributeTheirOwnCheckpointKey() throws Exception {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(TOPIC_L, "groupL", TOPIC_R, "groupR").addSink(v -> {
        });

        RedisJobClient job = env.executeAsync();
        try {
            assertEquals(List.of(TOPIC_L + "|groupL", TOPIC_R + "|groupR"), checkpointKeysOf(job),
                    "offsets are tracked per (topic, group), so both sides must be checkpointed");
        } finally {
            job.cancel();
        }
    }

    @Test
    void aSingleSourcePipelineStillContributesExactlyOneCheckpointKey() throws Exception {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopic(TOPIC_L, "groupL").addSink(v -> {
        });
        env.fromMqTopic(TOPIC_R, "groupR").addSink(v -> {
        });

        RedisJobClient job = env.executeAsync();
        try {
            assertEquals(List.of(TOPIC_L + "|groupL", TOPIC_R + "|groupR"), checkpointKeysOf(job));
        } finally {
            job.cancel();
        }
    }

    @Test
    void bothSourcesAreBootstrappedFromTheCommitFrontier() {
        RedisStreamExecutionEnvironment env = environment(baseConfig()
                .restoreConsumerGroupFromCommitFrontier(true).build());
        env.fromMqTopics(TOPIC_L, "groupL", TOPIC_R, "groupR").addSink(v -> {
        });

        RedisJobClient job = env.executeAsync();
        try {
            List<String> ensuredGroups = frontierEnsuredGroups();
            assertTrue(ensuredGroups.contains("groupL"), ensuredGroups.toString());
            assertTrue(ensuredGroups.contains("groupR"), ensuredGroups.toString());
        } finally {
            job.cancel();
        }
    }

    // ---------- the dual-source precondition ----------

    @Test
    void sourcesWithDifferentPartitionCountsFailAtStart() {
        partitionCounts.put(TOPIC_L, 2);
        partitionCounts.put(TOPIC_R, 3);
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(TOPIC_L, "groupL", TOPIC_R, "groupR").addSink(v -> {
        });

        IllegalStateException e = assertThrows(IllegalStateException.class, env::executeAsync);
        assertTrue(e.getMessage().contains(TOPIC_L), e.getMessage());
        assertTrue(e.getMessage().contains(TOPIC_R), e.getMessage());
        assertTrue(e.getMessage().contains("partition count"), e.getMessage());
    }

    @Test
    void sourcesWithEqualPartitionCountsStart() {
        partitionCounts.put(TOPIC_L, 2);
        partitionCounts.put(TOPIC_R, 2);
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(TOPIC_L, "groupL", TOPIC_R, "groupR").addSink(v -> {
        });

        RedisJobClient job = env.executeAsync();
        try {
            assertEquals(List.of(TOPIC_L, TOPIC_R), consumers.get(0).subscribedTopics());
        } finally {
            job.cancel();
        }
    }

    @Test
    void aSingleSourcePipelineIsNeverSubjectToThePartitionCountCheck() {
        partitionCounts.put(TOPIC_L, 2);
        partitionCounts.put(TOPIC_R, 7); // unrelated topic, never in the same pipeline
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopic(TOPIC_L, "groupL").addSink(v -> {
        });
        env.fromMqTopic(TOPIC_R, "groupR").addSink(v -> {
        });

        RedisJobClient job = env.executeAsync();
        try {
            assertEquals(2, consumers.size());
        } finally {
            job.cancel();
        }
    }

    // ---------- per-source watermark generators ----------

    /**
     * The factory form of {@code assignTimestampsAndWatermarks} builds one generator instance
     * per source, so a stateful generator (bounded out-of-orderness max tracking) only ever
     * sees its own source's records. A single shared instance would let the right source's max
     * raise the <em>left</em> source's watermark when the left record arrives second, and the
     * aligned watermark would jump to 4999 although the left source's own data is only at 1000.
     */
    @Test
    void thePerSourceGeneratorFactoryIsolatesStatefulGeneratorsAcrossSources() throws Exception {
        java.util.concurrent.atomic.AtomicInteger builds = new java.util.concurrent.atomic.AtomicInteger();
        List<Long> seen = new ArrayList<>();

        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(TOPIC_L, "groupL", TOPIC_R, "groupR")
                .map(m -> m)
                .assignTimestampsAndWatermarks(
                        (io.github.cuihairu.redis.streaming.api.watermark.TimestampAssigner<Message>)
                                (event, recordTimestamp) -> event.getTimestamp().toEpochMilli(),
                        () -> {
                            builds.incrementAndGet();
                            return new io.github.cuihairu.redis.streaming.watermark.generators
                                    .BoundedOutOfOrdernessWatermarkGenerator<Message>(java.time.Duration.ZERO);
                        })
                .keyBy(Message::getTopic)
                .process(new io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction<String, Message, Message>() {
                    @Override
                    public void processElement(String key, Message value,
                                               io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction.Context ctx,
                                               io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction.Collector<Message> out) {
                        seen.add(ctx.currentWatermark());
                        out.collect(value);
                    }
                })
                .addSink(v -> {
                });

        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            // the arrival order that breaks a shared instance: the fast source first
            handler.handle(messageWithTimestamp(TOPIC_R, 5000L));
            handler.handle(messageWithTimestamp(TOPIC_L, 1000L));

            assertEquals(2, builds.get(), "one generator per source, built lazily");
            assertEquals(List.of(Long.MIN_VALUE, 999L), seen,
                    "the left source's own data caps its raise at 999: no jump to the right's 4999");
        } finally {
            job.cancel();
        }
    }

    @Test
    void thePerSourceGeneratorFactoryAlsoHoldsInReverseArrivalOrder() throws Exception {
        List<Long> seen = new ArrayList<>();

        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        env.fromMqTopics(TOPIC_L, "groupL", TOPIC_R, "groupR")
                .map(m -> m)
                .assignTimestampsAndWatermarks(
                        (io.github.cuihairu.redis.streaming.api.watermark.TimestampAssigner<Message>)
                                (event, recordTimestamp) -> event.getTimestamp().toEpochMilli(),
                        () -> new io.github.cuihairu.redis.streaming.watermark.generators
                                .BoundedOutOfOrdernessWatermarkGenerator<Message>(java.time.Duration.ZERO))
                .keyBy(Message::getTopic)
                .process(new io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction<String, Message, Message>() {
                    @Override
                    public void processElement(String key, Message value,
                                               io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction.Context ctx,
                                               io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction.Collector<Message> out) {
                        seen.add(ctx.currentWatermark());
                        out.collect(value);
                    }
                })
                .addSink(v -> {
                });

        RedisJobClient job = env.executeAsync();
        try {
            MessageHandler handler = consumers.get(0).handler;
            handler.handle(messageWithTimestamp(TOPIC_L, 1000L));
            handler.handle(messageWithTimestamp(TOPIC_R, 5000L));

            assertEquals(List.of(Long.MIN_VALUE, 999L), seen,
                    "the slow source still caps the alignment: min(999, 4999)");
        } finally {
            job.cancel();
        }
    }

    // ---------- argument guards ----------

    @Test
    void fromMqTopicsValidatesItsArguments() {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        assertThrows(NullPointerException.class, () -> env.fromMqTopics(null, "g1", TOPIC_R, "g2"));
        assertThrows(NullPointerException.class, () -> env.fromMqTopics(TOPIC_L, null, TOPIC_R, "g2"));
        assertThrows(NullPointerException.class, () -> env.fromMqTopics(TOPIC_L, "g1", null, "g2"));
        assertThrows(NullPointerException.class, () -> env.fromMqTopics(TOPIC_L, "g1", TOPIC_R, null));
    }

    @Test
    void fromMqTopicsWithIdValidatesTheSourceId() {
        RedisStreamExecutionEnvironment env = environment(baseConfig().build());
        assertThrows(NullPointerException.class, () -> env.fromMqTopicsWithId(null, TOPIC_L, "g1", null,
                TOPIC_R, "g2", null));
        assertThrows(IllegalArgumentException.class, () -> env.fromMqTopicsWithId("  ", TOPIC_L, "g1", null,
                TOPIC_R, "g2", null));
        assertNotNull(env.fromMqTopicsWithId("ds.id-1", TOPIC_L, "g1", null, TOPIC_R, "g2", null));
        assertThrows(IllegalStateException.class, () -> env.fromMqTopicsWithId("ds.id-1", TOPIC_L, "g1", null,
                TOPIC_R, "g2", null));
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private static List<String> checkpointKeysOf(RedisJobClient job) throws Exception {
        Field field = job.getClass().getDeclaredField("pipelineKeys");
        field.setAccessible(true);
        List<RedisRuntimeCheckpointManager.PipelineKey> keys =
                (List<RedisRuntimeCheckpointManager.PipelineKey>) field.get(job);
        List<String> out = new ArrayList<>();
        for (RedisRuntimeCheckpointManager.PipelineKey key : keys) {
            out.add(key.key());
        }
        return out;
    }

    /**
     * The consumer groups handed to the ensure-group Lua script. The script call carries
     * {@code (mode, lua, returnType, keys, group, startId)}, so the group is argument 4.
     */
    private List<String> frontierEnsuredGroups() {
        List<String> groups = new ArrayList<>();
        for (org.mockito.invocation.Invocation invocation : mockingDetails(script).getInvocations()) {
            String method = invocation.getMethod().getName();
            if (!"eval".equals(method) || invocation.getArguments().length < 6) {
                continue;
            }
            Object keysArg = invocation.getArgument(3);
            if (!(keysArg instanceof List<?> keyList) || keyList.isEmpty()) {
                continue;
            }
            String streamKey = String.valueOf(keyList.get(0));
            if (streamKey.contains(TOPIC_L) || streamKey.contains(TOPIC_R)) {
                groups.add(String.valueOf((Object) invocation.getArgument(4)));
            }
        }
        return groups;
    }

    private static Message message(String topic, String id) {
        Message m = new Message();
        m.setId(id);
        m.setTopic(topic);
        m.setPayload("p");
        m.setHeaders(new HashMap<>(Map.of(MqHeaders.PARTITION_ID, "0")));
        return m;
    }

    private static Message messageWithTimestamp(String topic, long epochMillis) {
        Message m = message(topic, topic + "-" + epochMillis);
        m.setTimestamp(java.time.Instant.ofEpochMilli(epochMillis));
        return m;
    }

    /**
     * Records every subscription (the sibling mock consumer keeps only the last one, which cannot
     * express a two-source pipeline) and asserts the ordering that matters: all subscriptions are
     * in place before {@code start()}.
     */
    private static final class RecordingConsumer implements MessageConsumer {
        final List<String> topics = new ArrayList<>();
        final List<String> groups = new ArrayList<>();
        final List<SubscriptionOptions> options = new ArrayList<>();
        volatile MessageHandler handler;
        volatile boolean subscribedBeforeStart;
        volatile int starts;
        volatile boolean stopped;

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
            if (starts > 0) {
                subscribedBeforeStart = false;
            } else {
                subscribedBeforeStart = true;
            }
            this.handler = handler;
            this.topics.add(topic);
            this.groups.add(consumerGroup);
            this.options.add(options);
        }

        @Override
        public void unsubscribe(String topic) {
            topics.remove(topic);
        }

        @Override
        public void start() {
            starts++;
        }

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public void close() {
            stopped = true;
        }

        @Override
        public boolean isRunning() {
            return !stopped;
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        List<String> subscribedTopics() {
            return List.copyOf(topics);
        }

        List<String> subscribedGroups() {
            return List.copyOf(groups);
        }

        SubscriptionOptions optionsAt(int index) {
            return options.get(index);
        }

        void release() {
        }
    }
}