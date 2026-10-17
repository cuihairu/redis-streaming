package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Phase 3 leg 1: the multi-source carriers. A pipeline is defined once but reads several
 * {@code (topic, consumerGroup, SubscriptionOptions)} sources, and both the mutable definition
 * and the frozen pipeline must expose them per index while the historical single-source accessors
 * keep answering for source 0.
 *
 * <p>The guards are pinned here because the source lists are built by the public API from three
 * parallel user-supplied lists: a ragged list would silently mis-index a source's consumer group
 * onto another source's topic, which reads as data loss rather than a wiring bug.
 */
class RedisPipelineDualSourceUnitTest {

    private static final String TOPIC_L = "topicL";
    private static final String TOPIC_R = "topicR";

    private static RedisRuntimeConfig config() {
        return RedisRuntimeConfig.builder().jobName("dual-" + UUID.randomUUID().toString().substring(0, 6)).build();
    }

    private static SubscriptionOptions opts(int batchCount) {
        return SubscriptionOptions.builder().batchCount(batchCount).build();
    }

    private static RedisPipelineDefinition definition(List<String> topics,
                                                      List<String> groups,
                                                      List<SubscriptionOptions> options) {
        return new RedisPipelineDefinition(config(), mock(RedissonClient.class), new ObjectMapper(),
                topics, groups, options, List.of(), false);
    }

    private static List<RedisOperatorNode> noOperators() {
        return List.of();
    }

    private static List<StreamSink<Object>> sinks() {
        return List.of(v -> {
        });
    }

    // ---------- definition ----------

    @Test
    void theDefinitionExposesEverySourceByIndex() {
        SubscriptionOptions left = opts(7);
        SubscriptionOptions right = opts(11);
        RedisPipelineDefinition def = definition(
                List.of(TOPIC_L, TOPIC_R), List.of("groupL", "groupR"), Arrays.asList(left, right));

        assertEquals(2, def.sourceCount());
        assertEquals(TOPIC_L, def.topicAt(0));
        assertEquals(TOPIC_R, def.topicAt(1));
        assertEquals("groupL", def.consumerGroupAt(0));
        assertEquals("groupR", def.consumerGroupAt(1));
        assertSame(left, def.subscriptionOptionsAt(0));
        assertSame(right, def.subscriptionOptionsAt(1));

        // the historical accessors answer for the primary source, so existing single-source
        // callers (metrics, dedup keys, logs) keep working unchanged
        assertEquals(TOPIC_L, def.topic());
        assertEquals("groupL", def.consumerGroup());
        assertSame(left, def.subscriptionOptions());
    }

    @Test
    void theDefinitionResolvesASourceFromItsTopic() {
        RedisPipelineDefinition def = definition(
                List.of(TOPIC_L, TOPIC_R), List.of("groupL", "groupR"), Arrays.asList(null, null));

        assertEquals(0, def.sourceIndex(TOPIC_L));
        assertEquals(1, def.sourceIndex(TOPIC_R));
        assertEquals(-1, def.sourceIndex("nope"));
        assertEquals(-1, def.sourceIndex(null), "a null topic belongs to no source");
        assertNull(def.subscriptionOptionsAt(0), "a null per-source option means the MQ defaults");
    }

    @Test
    void aSingleSourceDefinitionIsASizedOneSourceList() {
        RedisPipelineDefinition def = definition(
                List.of(TOPIC_L), List.of("groupL"), Arrays.asList((SubscriptionOptions) null));

        assertEquals(1, def.sourceCount());
        assertEquals(TOPIC_L, def.topic());
        assertNull(def.subscriptionOptions());
    }

    @Test
    void theDefinitionRejectsAnEmptyOrRaggedSourceList() {
        RedisRuntimeConfig cfg = config();
        RedissonClient redisson = mock(RedissonClient.class);
        ObjectMapper om = new ObjectMapper();

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> new RedisPipelineDefinition(cfg, redisson, om, List.of(), List.of(), List.of(),
                        noOperators(), false));
        assertTrue(empty.getMessage().contains("at least one"), empty.getMessage());

        IllegalArgumentException ragged = assertThrows(IllegalArgumentException.class,
                () -> new RedisPipelineDefinition(cfg, redisson, om, List.of(TOPIC_L, TOPIC_R), List.of("groupL"),
                        List.of(opts(1)), noOperators(), false));
        assertTrue(ragged.getMessage().contains("same size"), ragged.getMessage());

        IllegalArgumentException nullTopic = assertThrows(IllegalArgumentException.class,
                () -> new RedisPipelineDefinition(cfg, redisson, om, Arrays.asList(TOPIC_L, null),
                        List.of("groupL", "groupR"), Arrays.asList(null, null), noOperators(), false));
        assertTrue(nullTopic.getMessage().contains("Source 1 topic"), nullTopic.getMessage());

        IllegalArgumentException nullGroup = assertThrows(IllegalArgumentException.class,
                () -> new RedisPipelineDefinition(cfg, redisson, om, List.of(TOPIC_L, TOPIC_R),
                        Arrays.asList("groupL", null), Arrays.asList(null, null), noOperators(), false));
        assertTrue(nullGroup.getMessage().contains("Source 1 consumerGroup"), nullGroup.getMessage());

        assertThrows(NullPointerException.class, () -> new RedisPipelineDefinition(cfg, redisson, om,
                null, List.of("groupL"), Arrays.asList((SubscriptionOptions) null), noOperators(), false));
    }

    @Test
    void theSingleSourceConstructorStillDelegatesToTheMultiSourceOne() {
        RedisPipelineDefinition def = new RedisPipelineDefinition(config(), mock(RedissonClient.class),
                new ObjectMapper(), TOPIC_L, "groupL", opts(3), noOperators(), false);

        assertEquals(1, def.sourceCount());
        assertEquals(3, def.subscriptionOptionsAt(0).getBatchCount());
        assertThrows(NullPointerException.class, () -> new RedisPipelineDefinition(config(),
                mock(RedissonClient.class), new ObjectMapper(), null, "groupL", null, noOperators(), false));
    }

    @Test
    void freezingCarriesEverySourceIntoTheFrozenPipeline() {
        RedisPipelineDefinition def = definition(
                List.of(TOPIC_L, TOPIC_R), List.of("groupL", "groupR"), Arrays.asList(opts(1), opts(2)));
        def.addSink(v -> {
        });

        RedisPipeline<Object> frozen = def.freeze();
        assertEquals(2, frozen.sourceCount());
        assertEquals(TOPIC_L, frozen.topicAt(0));
        assertEquals("groupR", frozen.consumerGroupAt(1));
        assertEquals(2, frozen.subscriptionOptionsAt(1).getBatchCount());
        assertEquals(1, frozen.sourceIndex(TOPIC_R));
    }

    @Test
    void freezingWithoutASinkStillFails() {
        RedisPipelineDefinition def = definition(
                List.of(TOPIC_L, TOPIC_R), List.of("groupL", "groupR"), Arrays.asList(null, null));
        IllegalStateException e = assertThrows(IllegalStateException.class, def::freeze);
        assertTrue(e.getMessage().contains(TOPIC_L), e.getMessage());
    }

    // ---------- frozen pipeline ----------

    @Test
    void theFrozenPipelineBuildsARunnerOverEverySource() {
        RedisPipeline<Object> pipeline = RedisPipeline.forMqSource(config(), mock(RedissonClient.class),
                new ObjectMapper(), List.of(TOPIC_L, TOPIC_R), List.of("groupL", "groupR"),
                Arrays.asList(null, null), noOperators(), sinks(), false);

        RedisPipelineRunner<Object> runner = pipeline.buildRunner();
        try {
            // the runner resolves an arriving record's source from its Message topic, which is how
            // one handler knows which side of the join a record came from
            assertEquals(0, runner.sourceIndexOf(message(TOPIC_L)));
            assertEquals(1, runner.sourceIndexOf(message(TOPIC_R)));
        } finally {
            runner.close();
        }
    }

    @Test
    void theFrozenPipelineRejectsAnEmptyOrRaggedSourceList() {
        RedisRuntimeConfig cfg = config();
        RedissonClient redisson = mock(RedissonClient.class);
        ObjectMapper om = new ObjectMapper();

        assertThrows(IllegalArgumentException.class, () -> RedisPipeline.forMqSource(cfg, redisson, om,
                List.of(), List.of(), List.of(), noOperators(), sinks(), false));
        IllegalArgumentException ragged = assertThrows(IllegalArgumentException.class,
                () -> RedisPipeline.forMqSource(cfg, redisson, om, List.of(TOPIC_L, TOPIC_R), List.of("groupL"),
                        List.of(opts(1)), noOperators(), sinks(), false));
        assertTrue(ragged.getMessage().contains("same size"), ragged.getMessage());
    }

    @Test
    void theFrozenSingleSourceFactoryIsASizedOneSourceList() {
        RedisPipeline<Object> pipeline = RedisPipeline.forMqSource(config(), mock(RedissonClient.class),
                new ObjectMapper(), TOPIC_L, "groupL", opts(5), noOperators(), sinks());

        assertEquals(1, pipeline.sourceCount());
        assertEquals(TOPIC_L, pipeline.topic());
        assertEquals(5, pipeline.subscriptionOptionsAt(0).getBatchCount());
        assertEquals(-1, pipeline.sourceIndex("other"));
    }

    private static io.github.cuihairu.redis.streaming.mq.Message message(String topic) {
        io.github.cuihairu.redis.streaming.mq.Message m = new io.github.cuihairu.redis.streaming.mq.Message();
        m.setTopic(topic);
        return m;
    }
}