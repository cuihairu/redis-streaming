package io.github.cuihairu.redis.streaming.runtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisOperatorNode;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipeline;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineDefinition;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineRunner;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisStreamBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 leg 1: the dual-source pipeline topology — one pipeline consuming two MQ topics
 * ({@code RedisStreamExecutionEnvironment.fromMqTopics}).
 *
 * <p>Wiring a pipeline only compiles operators and registers definitions, so a mocked Redisson
 * client is enough: no I/O happens here (the same split as {@link RedisEnvJoinSugarTest}'s
 * topology half). The end-to-end leg lives in {@code RedisDualSourceJoinIntegrationTest}.
 *
 * <p>The guards are the load-bearing part: a dual-source pipeline claims <em>two</em>
 * (topic, group) pairs, so a collision on either one has to be caught at registration time — two
 * pipelines sharing one consumer group split the stream between them (Redis Streams
 * consumer-group semantics) and would silently halve the join's input.
 */
class RedisDualSourcePipelineTopologyTest {

    private static final String TOPIC_L = "dsl-l";
    private static final String TOPIC_R = "dsl-r";

    /** Wiring never touches Redis, so a mocked client is enough to compile and register pipelines. */
    private static RedisStreamExecutionEnvironment env() {
        return RedisStreamExecutionEnvironment.create(
                Mockito.mock(RedissonClient.class),
                RedisRuntimeConfig.builder().jobName("dual-source-ut").build());
    }

    private static RedisRuntimeConfig config() {
        return RedisRuntimeConfig.builder().jobName("dual-source-def-ut").build();
    }

    private static RedisOperatorNode passthrough() {
        return (value, ctx, emit) -> emit.emit(value);
    }

    private static Message message(String topic, long epochMillis) {
        Message m = new Message();
        m.setId(topic + "-" + epochMillis);
        m.setTopic(topic);
        return m;
    }

    /**
     * The environment's registered definitions. {@code addSink} registers lazily and exposes
     * nothing back, so the registry is read reflectively (the pattern the other env tests use).
     */
    private static List<RedisPipelineDefinition> definitionsOf(RedisStreamExecutionEnvironment env) {
        try {
            Field f = RedisStreamExecutionEnvironment.class.getDeclaredField("pipelineDefinitions");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<RedisPipelineDefinition> defs = (List<RedisPipelineDefinition>) f.get(env);
            return List.copyOf(defs);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read the registered pipeline definitions", e);
        }
    }

    private static RedisPipelineDefinition definitionOf(RedisStreamExecutionEnvironment env) {
        List<RedisPipelineDefinition> defs = definitionsOf(env);
        assertEquals(1, defs.size(), "expected exactly one registered pipeline");
        return defs.get(0);
    }

    // ---------- the source builder ----------

    @Test
    void fromMqTopicsRegistersOnePipelineWithTwoSources() {
        RedisStreamExecutionEnvironment env = env();

        env.fromMqTopics(TOPIC_L, "gl", TOPIC_R, "gr")
                .map(m -> String.valueOf(m.getPayload()))
                .addSink(v -> {
                });

        RedisPipelineDefinition def = definitionOf(env);
        assertEquals(2, def.sourceCount());
        assertEquals(TOPIC_L, def.topicAt(0));
        assertEquals("gl", def.consumerGroupAt(0));
        assertEquals(TOPIC_R, def.topicAt(1));
        assertEquals("gr", def.consumerGroupAt(1));
        // source 0 stays the pipeline's primary identity (keyed state is keyed by it)
        assertEquals(TOPIC_L, def.topic());
        assertEquals("gl", def.consumerGroup());
        assertNull(def.subscriptionOptionsAt(0), "the convenience overload leaves the MQ defaults in place");
        assertEquals(1, def.sourceIndex(TOPIC_R));
        assertEquals(0, def.sourceIndex(TOPIC_L));
        assertEquals(-1, def.sourceIndex("not-a-source"));
    }

    @Test
    void fromMqTopicsCarriesPerSourceSubscriptionOptions() {
        RedisStreamExecutionEnvironment env = env();
        SubscriptionOptions left = SubscriptionOptions.builder().batchCount(7).build();
        SubscriptionOptions right = SubscriptionOptions.builder().batchCount(9).build();

        env.fromMqTopics(TOPIC_L, "gl", left, TOPIC_R, "gr", right)
                .map(m -> String.valueOf(m.getPayload()))
                .addSink(v -> {
                });

        RedisPipelineDefinition def = definitionOf(env);
        assertSame(left, def.subscriptionOptionsAt(0));
        assertSame(right, def.subscriptionOptionsAt(1));
        assertSame(left, def.subscriptionOptions(), "the primary accessors read source 0");
    }

    @Test
    void fromMqTopicsRejectsNullTopicsAndGroups() {
        RedisStreamExecutionEnvironment env = env();

        assertThrows(NullPointerException.class, () -> env.fromMqTopics(null, "gl", TOPIC_R, "gr"));
        assertThrows(NullPointerException.class, () -> env.fromMqTopics(TOPIC_L, null, TOPIC_R, "gr"));
        assertThrows(NullPointerException.class, () -> env.fromMqTopics(TOPIC_L, "gl", null, "gr"));
        assertThrows(NullPointerException.class, () -> env.fromMqTopics(TOPIC_L, "gl", TOPIC_R, null));
    }

    @Test
    void fromMqTopicsWithIdKeepsTheStableSourceId() {
        RedisStreamExecutionEnvironment env = env();

        env.fromMqTopicsWithId("ds-stable", TOPIC_L, "gl", null, TOPIC_R, "gr", null)
                .map(m -> String.valueOf(m.getPayload()))
                .addSink(v -> {
                });

        assertEquals(2, definitionOf(env).sourceCount());
        // the id charset guard stays in force for the dual-source form
        assertThrows(IllegalArgumentException.class,
                () -> env.fromMqTopicsWithId("ds stable", TOPIC_L, "gl2", null, TOPIC_R, "gr2", null));
        assertThrows(NullPointerException.class,
                () -> env.fromMqTopicsWithId(null, TOPIC_L, "gl3", null, TOPIC_R, "gr3", null));
        assertThrows(NullPointerException.class,
                () -> env.fromMqTopicsWithId("ds-2", null, "gl3", null, TOPIC_R, "gr3", null));
    }

    @Test
    void twoDualSourcePipelinesMayShareATopicUnderDifferentGroups() {
        RedisStreamExecutionEnvironment env = env();

        env.fromMqTopics(TOPIC_L, "gl-a", TOPIC_R, "gr-a").map(m -> m).addSink(v -> {
        });
        // same topics, different consumer groups: two independent chains over the same input
        env.fromMqTopics(TOPIC_L, "gl-b", TOPIC_R, "gr-b").map(m -> m).addSink(v -> {
        });

        assertEquals(2, definitionsOf(env).size());
    }

    // ---------- the duplicate-source guard, per source ----------

    @Test
    void aDualSourcePipelineCollidesOnItsSecondSource() {
        RedisStreamExecutionEnvironment env = env();
        // a single-source pipeline already owns the right topic/group
        env.fromMqTopic(TOPIC_R, "gr").map(m -> m).addSink(v -> {
        });

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> env.fromMqTopics(TOPIC_L, "gl", TOPIC_R, "gr").map(m -> m).addSink(v -> {
                }));

        assertTrue(e.getMessage().contains("Duplicate pipeline"), e.getMessage());
        assertTrue(e.getMessage().contains(TOPIC_R), e.getMessage());
        assertTrue(e.getMessage().contains("gr"), e.getMessage());
    }

    @Test
    void aSingleSourcePipelineCollidesWithADualSourcePipeline() {
        RedisStreamExecutionEnvironment env = env();
        env.fromMqTopics(TOPIC_L, "gl", TOPIC_R, "gr").map(m -> m).addSink(v -> {
        });

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> env.fromMqTopic(TOPIC_L, "gl").map(m -> m).addSink(v -> {
                }));

        assertTrue(e.getMessage().contains("Duplicate pipeline"), e.getMessage());
        assertTrue(e.getMessage().contains(TOPIC_L), e.getMessage());
        assertTrue(e.getMessage().contains("gl"), e.getMessage());
    }

    @Test
    void twoDualSourcePipelinesSharingOneSourceCollide() {
        RedisStreamExecutionEnvironment env = env();
        env.fromMqTopics(TOPIC_L, "gl", TOPIC_R, "gr").map(m -> m).addSink(v -> {
        });

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> env.fromMqTopics(TOPIC_L, "gl", TOPIC_R, "gr-2").map(m -> m).addSink(v -> {
                }));

        assertTrue(e.getMessage().contains(TOPIC_L), e.getMessage());
    }

    // ---------- the definition and the frozen pipeline ----------

    @Test
    void theDefinitionRejectsAnEmptyOrRaggedSourceList() {
        RedissonClient redisson = Mockito.mock(RedissonClient.class);
        ObjectMapper mapper = new ObjectMapper();

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> new RedisPipelineDefinition(config(), redisson, mapper,
                        List.of(), List.of(), List.of(), List.of(), false));
        assertTrue(empty.getMessage().contains("at least one"), empty.getMessage());

        IllegalArgumentException ragged = assertThrows(IllegalArgumentException.class,
                () -> new RedisPipelineDefinition(config(), redisson, mapper,
                        List.of(TOPIC_L, TOPIC_R), List.of("gl"), Arrays.asList(null, null),
                        List.of(), false));
        assertTrue(ragged.getMessage().contains("same size"), ragged.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> new RedisPipelineDefinition(config(), redisson, mapper,
                        List.of(TOPIC_L, TOPIC_R), Arrays.asList("gl", null), Arrays.asList(null, null),
                        List.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisPipelineDefinition(config(), redisson, mapper,
                        Arrays.asList(TOPIC_L, null), List.of("gl", "gr"), Arrays.asList(null, null),
                        List.of(), false), "a null topic entry is a malformed source list, reported"
                        + " with the offending index rather than as a bare NPE");
        assertThrows(NullPointerException.class,
                () -> new RedisPipelineDefinition(config(), redisson, mapper,
                        null, List.of("gl"), Arrays.asList((SubscriptionOptions) null), List.of(), false));
    }

    @Test
    void aFrozenDualSourcePipelineExposesBothSourcesAndBuildsOneRunner() throws Exception {
        RedisPipelineDefinition def = new RedisPipelineDefinition(config(),
                Mockito.mock(RedissonClient.class), new ObjectMapper(),
                List.of(TOPIC_L, TOPIC_R), List.of("gl", "gr"), Arrays.asList(null, null),
                List.of(passthrough()), false);
        def.addSink(v -> {
        });
        RedisPipeline<Object> pipeline = def.freeze();

        assertEquals(2, pipeline.sourceCount());
        assertEquals(TOPIC_L, pipeline.topicAt(0));
        assertEquals("gr", pipeline.consumerGroupAt(1));
        assertNull(pipeline.subscriptionOptionsAt(1), "a null entry means the MQ defaults");
        assertEquals(TOPIC_L, pipeline.topic());
        assertEquals("gl", pipeline.consumerGroup());
        assertNull(pipeline.subscriptionOptions());
        assertEquals(1, pipeline.sourceIndex(TOPIC_R));
        assertEquals(-1, pipeline.sourceIndex("nope"));

        // one runner per subtask, consuming every source of the pipeline
        RedisPipelineRunner<Object> runner = pipeline.buildRunner();
        try {
            assertEquals(1, runner.sourceIndexOf(message(TOPIC_R, 1L)));
            assertEquals(0, runner.sourceIndexOf(message(TOPIC_L, 1L)));
        } finally {
            runner.close();
        }
    }

    @Test
    void freezingADualSourcePipelineWithoutSinksIsRejected() {
        RedisPipelineDefinition def = new RedisPipelineDefinition(config(),
                Mockito.mock(RedissonClient.class), new ObjectMapper(),
                List.of(TOPIC_L, TOPIC_R), List.of("gl", "gr"), Arrays.asList(null, null),
                List.of(passthrough()), false);

        IllegalStateException e = assertThrows(IllegalStateException.class, def::freeze);
        assertTrue(e.getMessage().contains(TOPIC_L), e.getMessage());
        assertTrue(e.getMessage().contains("gl"), e.getMessage());
    }

    @Test
    void theSourceBuilderRejectsAnEmptyOrRaggedSourceList() {
        RedisStreamExecutionEnvironment env = env();
        ObjectMapper mapper = new ObjectMapper();

        assertThrows(NullPointerException.class,
                () -> RedisStreamBuilder.forMqSources(env, config(), null, mapper, "s0", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> RedisStreamBuilder.forMqSources(env, config(), Mockito.mock(RedissonClient.class), mapper,
                        "s0", List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> RedisStreamBuilder.forMqSources(env, config(), Mockito.mock(RedissonClient.class), mapper,
                        "s0", List.of(TOPIC_L, TOPIC_R), List.of("gl"), Arrays.asList(null, null)));
    }

    @Test
    void aSingleSourcePipelineStillReadsAsOneSource() {
        RedisStreamExecutionEnvironment env = env();

        env.fromMqTopic(TOPIC_L, "gl").map(m -> m).addSink(v -> {
        });

        RedisPipelineDefinition def = definitionOf(env);
        assertEquals(1, def.sourceCount());
        assertEquals(TOPIC_L, def.topicAt(0));
        assertEquals(-1, def.sourceIndex(TOPIC_R), "a single-source pipeline must not claim the peer topic");
    }
}