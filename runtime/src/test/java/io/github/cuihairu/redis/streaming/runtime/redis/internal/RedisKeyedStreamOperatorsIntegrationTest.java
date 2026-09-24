package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.ReduceFunction;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the keyed (non-windowed) {@code reduce}/{@code sum} operators of
 * {@code RedisKeyedStreamBuilder} on real Redis, including their serialize/deserialize
 * and guard error paths plus natural encodeNumber/decodeNumber usage.
 */
@Tag("integration")
class RedisKeyedStreamOperatorsIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    private static RedisRuntimeConfig cfg(String prefix) {
        return RedisRuntimeConfig.builder()
                .jobName("it-rti-keyed-" + prefix)
                .stateKeyPrefix("it-rti-keyed:" + prefix)
                .build();
    }

    private static Message msg(long eventTimeMs, Object payload) {
        Message m = new Message();
        m.setId("m-" + eventTimeMs + "-" + UUID.randomUUID().toString().substring(0, 6));
        m.setTimestamp(Instant.ofEpochMilli(eventTimeMs));
        m.setPayload(payload);
        m.setHeaders(Map.of("partitionId", "0"));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static RedisPipelineDefinition definitionOf(DataStream<?> stream) throws Exception {
        Field f = RedisStreamBuilder.class.getDeclaredField("registeredDefinition");
        f.setAccessible(true);
        return (RedisPipelineDefinition) f.get(stream);
    }

    private static RMap<String, String> findStateMap(RedissonClient redis, String prefix, String field) {
        for (String key : redis.getKeys().getKeysByPattern(prefix + "*")) {
            if (redis.getKeys().getType(key) == RType.MAP) {
                RMap<String, String> map = redis.getMap(key, StringCodec.INSTANCE);
                if (map.containsKey(field)) {
                    return map;
                }
            }
        }
        throw new IllegalStateException("no state map containing field " + field + " under " + prefix);
    }

    @Test
    void keyedReduceAccumulatesPerKey() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            List<Object> out = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg(uid));
            DataStream<String> tail = env.fromMqTopic("it-rti-t-" + uid, "g")
                    .map(m -> (String) m.getPayload())
                    .keyBy(v -> "k")
                    .reduce((ReduceFunction<String>) (a, b) -> a + "|" + b)
                    .addSink(out::add);
            RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
            try {
                runner.handle(msg(1000, "a"));
                runner.handle(msg(1100, "b"));
            } finally {
                runner.close();
            }
            assertEquals(List.of("a", "a|b"), out);
        } finally {
            redis.getKeys().deleteByPattern("it-rti-keyed:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void keyedReduceErrorPaths() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            // corrupt stored state -> deserialize failure
            List<Object> out = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg(uid));
            DataStream<String> tail = env.fromMqTopic("it-rti-t-" + uid, "g")
                    .map(m -> (String) m.getPayload())
                    .keyBy(v -> "k")
                    .reduce((ReduceFunction<String>) (a, b) -> a + "|" + b)
                    .addSink(out::add);
            RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
            try {
                runner.handle(msg(1000, "x"));
                RMap<String, String> state = findStateMap(redis, "it-rti-keyed:" + uid, "s:k");
                state.put("s:k", "not-json");
                RuntimeException e1 = assertThrows(RuntimeException.class,
                        () -> runner.handle(msg(1100, "y")));
                assertTrue(e1.getMessage().contains("Failed to deserialize reduce state"), "" + e1);
            } finally {
                runner.close();
            }

            // reducer failure
            List<Object> boomOut = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment envB = RedisStreamExecutionEnvironment.create(redis, cfg(uid + "b"));
            DataStream<String> tailB = envB.fromMqTopic("it-rti-t-" + uid + "b", "g")
                    .map(m -> (String) m.getPayload())
                    .keyBy(v -> "k")
                    .reduce((ReduceFunction<String>) (a, b) -> {
                        throw new IllegalStateException("reduce-boom");
                    })
                    .addSink(boomOut::add);
            RedisPipelineRunner<Object> runnerB = definitionOf(tailB).freeze().buildRunner();
            try {
                runnerB.handle(msg(1000, "x"));
                RuntimeException e2 = assertThrows(RuntimeException.class,
                        () -> runnerB.handle(msg(1100, "y")));
                assertTrue(e2.getMessage().contains("Reduce function failed"), "" + e2);
            } finally {
                runnerB.close();
            }

            // reducer returning null removes state
            List<Object> nullOut = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env2 = RedisStreamExecutionEnvironment.create(redis, cfg(uid + "n"));
            DataStream<String> tail2 = env2.fromMqTopic("it-rti-t-" + uid + "n", "g")
                    .map(m -> (String) m.getPayload())
                    .keyBy(v -> "k")
                    .reduce((ReduceFunction<String>) (a, b) -> null)
                    .addSink(nullOut::add);
            RedisPipelineRunner<Object> runner2 = definitionOf(tail2).freeze().buildRunner();
            try {
                runner2.handle(msg(1000, "x"));
                RMap<String, String> state2 = findStateMap(redis, "it-rti-keyed:" + uid + "n", "s:k");
                runner2.handle(msg(1100, "y"));
                assertNull(state2.get("s:k"), "null reduce result must clear state");
            } finally {
                runner2.close();
            }

            // serialization failure
            List<Object> evilOut = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env3 = RedisStreamExecutionEnvironment.create(redis, cfg(uid + "e"));
            DataStream<Object> tail3 = env3.fromMqTopic("it-rti-t-" + uid + "e", "g")
                    .map(m -> m.getPayload())
                    .keyBy(v -> "k")
                    .reduce((ReduceFunction<Object>) (a, b) -> a)
                    .addSink(evilOut::add);
            RedisPipelineRunner<Object> runner3 = definitionOf(tail3).freeze().buildRunner();
            try {
                RuntimeException e3 = assertThrows(RuntimeException.class,
                        () -> runner3.handle(msg(1000, new Evil())));
                assertTrue(e3.getMessage().contains("Failed to serialize reduce state"), "" + e3);
            } finally {
                runner3.close();
            }
        } finally {
            redis.getKeys().deleteByPattern("it-rti-keyed:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void keyedSumEncodesNumbersAndToleratesCorruptState() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            List<Object> out = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg(uid));
            DataStream<Object> tail = env.fromMqTopic("it-rti-t-" + uid, "g")
                    .map(m -> m.getPayload())
                    .keyBy(v -> "k")
                    .sum(v -> (Number) v)
                    .addSink(out::add);
            RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
            try {
                runner.handle(msg(1000, 3));
                runner.handle(msg(1100, 4));
                runner.handle(msg(1200, 1.5d));
                runner.handle(msg(1300, 2.5d));
                assertEquals(List.of(3, 7, 8.5d, 11.0d), out);

                // corrupt state decodes as 0 and keeps summing
                RMap<String, String> state = findStateMap(redis, "it-rti-keyed:" + uid, "s:k");
                state.put("s:k", "garbage");
                runner.handle(msg(1400, 5));
                assertEquals(5, out.get(out.size() - 1));

                // non-Number elements are rejected
                assertThrows(UnsupportedOperationException.class,
                        () -> runner.handle(msg(1500, "str")));
            } finally {
                runner.close();
            }
        } finally {
            redis.getKeys().deleteByPattern("it-rti-keyed:" + uid + "*");
            redis.shutdown();
        }
    }

    public static final class Evil {
        public String getBoom() {
            throw new IllegalStateException("no-serialization");
        }
    }
}
