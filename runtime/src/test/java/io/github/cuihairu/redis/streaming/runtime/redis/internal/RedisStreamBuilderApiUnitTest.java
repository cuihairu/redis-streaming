package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit coverage for {@link RedisStreamBuilder} transform operators (filter/flatMap/print),
 * {@code encodeNumber}/{@code decodeNumber}, the anonymous watermark output
 * (markIdle/markActive) plus {@link RedisPipelineDefinition} and {@link RedisPipeline}
 * freeze/buildRunner/copySinks plumbing. No Redis required.
 */
class RedisStreamBuilderApiUnitTest {

    private static Message msg(long eventTimeMs, Object payload) {
        Message m = new Message();
        m.setId("m-" + eventTimeMs);
        m.setTimestamp(Instant.ofEpochMilli(eventTimeMs));
        m.setPayload(payload);
        m.setHeaders(java.util.Map.of("partitionId", "0"));
        return m;
    }

    private static RedisStreamExecutionEnvironment env(RedissonClient redis, RedisRuntimeConfig cfg) {
        return RedisStreamExecutionEnvironment.create(redis, cfg);
    }

    @SuppressWarnings("unchecked")
    private static RedisPipelineDefinition definitionOf(DataStream<?> stream) throws Exception {
        Field f = RedisStreamBuilder.class.getDeclaredField("registeredDefinition");
        f.setAccessible(true);
        return (RedisPipelineDefinition) f.get(stream);
    }

    @Test
    void filterFlatMapAndPrintLambdasRunThroughThePipeline() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("it-rti-bapi")
                .stateKeyPrefix("it-rti-bapi")
                .build();
        RedisStreamExecutionEnvironment env = env(redis, cfg);

        List<Object> out = new CopyOnWriteArrayList<>();
        DataStream<String> stream = env.fromMqTopic("topicA", "groupA")
                .map(m -> (String) m.getPayload())
                .filter(s -> s.startsWith("a"))
                .flatMap(s -> s.length() > 1 ? List.of(s, s.toUpperCase()) : null)
                .print("P")
                .print()
                .addSink(out::add);

        RedisPipelineDefinition def = definitionOf(stream);
        assertNotNull(def);
        assertTrue(def.hasSinks());
        RedisPipeline<Object> pipeline = def.freeze();
        assertNotNull(pipeline.topic());
        assertNotNull(pipeline.consumerGroup());

        RedisPipelineRunner<Object> runner = pipeline.buildRunner();
        try {
            runner.handle(msg(1000, "ab"));
            runner.handle(msg(1100, "zz"));
            runner.handle(msg(1200, "a"));
        } finally {
            runner.close();
        }
        assertEquals(List.of("ab", "AB"), out);
    }

    @Test
    void noArgPrintRegistersSinkToo() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("it-rti-bapi-print")
                .stateKeyPrefix("it-rti-bapi-print")
                .build();
        DataStream<String> stream = env(redis, cfg)
                .fromMqTopic("topicA", "groupA")
                .map(m -> (String) m.getPayload())
                .print();
        RedisPipelineDefinition def = definitionOf(stream);
        assertTrue(def.hasSinks());
        RedisPipelineRunner<Object> runner = def.freeze().buildRunner();
        try {
            runner.handle(msg(1000, "hello"));
        } finally {
            runner.close();
        }
    }

    @Test
    void pipelineDefinitionFreezeRequiresSinks() {
        RedissonClient redis = mock(RedissonClient.class);
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("it-rti-bapi-freeze")
                .stateKeyPrefix("it-rti-bapi-freeze")
                .build();
        RedisPipelineDefinition def = new RedisPipelineDefinition(
                cfg, redis, new ObjectMapper(), "topicA", "groupA", null, List.of());
        assertFalse(def.hasSinks());
        assertThrows(IllegalStateException.class, def::freeze);
        def.addSink(v -> {});
        assertTrue(def.hasSinks());
        assertNotNull(def.freeze());
    }

    @Test
    void pipelineCopySinksCastsAndCopies() {
        io.github.cuihairu.redis.streaming.api.stream.StreamSink<Object> sink = v -> {};
        List<io.github.cuihairu.redis.streaming.api.stream.StreamSink<Object>> copied =
                RedisPipeline.copySinks(java.util.Arrays.asList(sink, null));
        assertEquals(2, copied.size());
    }

    @Test
    void encodeNumberAndDecodeNumberCoverAllBranches() throws Exception {
        Method encode = RedisStreamBuilder.class.getDeclaredMethod("encodeNumber", Number.class);
        encode.setAccessible(true);
        assertEquals("l:0", encode.invoke(null, new Object[]{null}));
        assertEquals("d:1.5", encode.invoke(null, 1.5d));
        assertEquals("d:2.0", encode.invoke(null, 2.0f));
        assertEquals("l:5", encode.invoke(null, 5L));
        assertEquals("l:7", encode.invoke(null, 7));

        Method decode = RedisStreamBuilder.class.getDeclaredMethod("decodeNumber", String.class);
        decode.setAccessible(true);
        assertEquals(0L, decode.invoke(null, new Object[]{null}));
        assertEquals(0L, decode.invoke(null, ""));
        assertEquals(1.5d, (Double) decode.invoke(null, "d:1.5"), 1e-9);
        assertEquals(3L, decode.invoke(null, "l:3"));
        assertEquals(42L, decode.invoke(null, "42"));
        assertEquals(2.25d, (Double) decode.invoke(null, "2.25"), 1e-9);
        assertEquals(0L, decode.invoke(null, "garbage"));
    }

    @Test
    void anonymousWatermarkOutputMarkIdleAndMarkActiveRun() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("it-rti-bapi-wm")
                .stateKeyPrefix("it-rti-bapi-wm")
                .build();
        List<Object> out = new CopyOnWriteArrayList<>();

        WatermarkGenerator<String> generator = new WatermarkGenerator<>() {
            @Override
            public void onEvent(String event, long eventTimestamp, WatermarkOutput output) {
                output.markIdle();
                output.markActive();
                output.emitWatermark(new Watermark(eventTimestamp));
                output.emitWatermark(null);
            }

            @Override
            public void onPeriodicEmit(WatermarkOutput output) {
                output.markIdle();
                output.markActive();
                output.emitWatermark(Watermark.maxWatermark());
            }
        };

        DataStream<String> stream = env(redis, cfg)
                .fromMqTopic("topicA", "groupA")
                .map(m -> (String) m.getPayload())
                .assignTimestampsAndWatermarks(generator)
                .print("WM");
        RedisPipelineRunner<Object> runner = definitionOf(stream).freeze().buildRunner();
        try {
            runner.handle(msg(1000, "x"));
            runner.handle(msg(2000, "y"));
        } finally {
            runner.close();
        }
    }
}
