package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Deep storms for runtime internals (state store / checkpoint manager / window ops). */
@Tag("integration")
class RuntimeDeepStormTest {

    private static RedisRuntimeConfig cfg(String job) {
        return RedisRuntimeConfig.builder().jobName(job).stateKeyPrefix("deep:" + job)
                .checkpointInterval(Duration.ofSeconds(30)).build();
    }

    @Test
    void checkpointManagerAndStoreDeepStormOnRealRedis() throws Exception {
        RedissonClient client = Storms.constructing(RedisDeepStormSupport::client);
        try {
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(client, cfg("dm-" + System.nanoTime()));
            assertTrue(Storms.stormDeep(mgr, Map0(), 500, "close") > 8);

            RedisKeyedStateStore<String> store = new RedisKeyedStateStore<>(client,
                    new com.fasterxml.jackson.databind.ObjectMapper(), "deep:ks-" + System.nanoTime(), "job",
                    "t", "g", "op", Duration.ofMillis(500), 2, 3, 1, Duration.ofMillis(1),
                    true, RedisRuntimeConfig.StateSchemaMismatchPolicy.IGNORE);
            store.setCurrentPartitionId(0);
            store.setCurrentKey("k");
            assertTrue(Storms.stormDeep(store, Map0(), 500) > 8);
            store.clearCurrentKey();
            store.clearCurrentPartitionId();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void environmentAndWindowedDeepStorm() {
        RedissonClient boom = Storms.exploding(RedissonClient.class);
        RedisStreamExecutionEnvironment env = Storms.constructing(
                () -> RedisStreamExecutionEnvironment.create(boom, cfg("env-deep")));
        assertTrue(Storms.stormDeep(env, Map0(), 300, "executeAsync", "execute") > 8);

        RedissonClient deep = Storms.deep(RedissonClient.class);
        RedisStreamExecutionEnvironment happy = Storms.constructing(
                () -> RedisStreamExecutionEnvironment.create(deep, cfg("env-deep2")));
        io.github.cuihairu.redis.streaming.api.stream.KeyedStream<String, String> keyed = Storms.constructing(
                () -> happy.fromMqTopic("t", "g").map(m -> (String) m.getPayload()).keyBy(k -> k));
        io.github.cuihairu.redis.streaming.api.stream.WindowedStream<String, String> windowed =
                keyed.window(io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow.ofMillis(1000));
        assertTrue(Storms.stormDeep(windowed, Map0(), 300) >= 5);
    }

    private static java.util.Map<Class<?>, Object> Map0() {
        return java.util.Map.of();
    }
}
