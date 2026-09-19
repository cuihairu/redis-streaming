package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Error-path storms for the Redis runtime internals: checkpoint manager, keyed state store,
 * pipeline runner. All collaborators throw once armed, exercising fallback plumbing.
 */
class RuntimeInternalStormTest {

    private static RedisRuntimeConfig cfg(String job) {
        return RedisRuntimeConfig.builder()
                .jobName(job)
                .stateKeyPrefix("storm:" + job)
                .checkpointInterval(Duration.ofMillis(50))
                .build();
    }

    @Test
    void checkpointManagerStorm() {
        RedisRuntimeCheckpointManager happy = Storms.constructing(
                () -> new RedisRuntimeCheckpointManager(Storms.deep(RedissonClient.class), cfg("ckm-h")));
        assertTrue(Storms.storm(happy, null) > 5);
        RedisRuntimeCheckpointManager failing = Storms.constructing(
                () -> new RedisRuntimeCheckpointManager(Storms.exploding(RedissonClient.class), cfg("ckm-f")));
        assertTrue(Storms.storm(failing, null) > 5);
    }

    @Test
    void keyedStateStoreStorm() {
        RedisKeyedStateStore<String> happy = Storms.constructing(() -> new RedisKeyedStateStore<>(
                Storms.deep(RedissonClient.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                "storm:ks-h", "job", "t", "g", "op", Duration.ofMinutes(1), 3, 2, 5,
                Duration.ofMillis(1), true, RedisRuntimeConfig.StateSchemaMismatchPolicy.CLEAR));
        happy.setCurrentPartitionId(0);
        happy.setCurrentKey("k");
        assertTrue(Storms.storm(happy, null) > 5);
        happy.clearCurrentKey();
        happy.clearCurrentPartitionId();

        RedisKeyedStateStore<String> failing = Storms.constructing(() -> new RedisKeyedStateStore<>(
                Storms.exploding(RedissonClient.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                "storm:ks-f", "job", "t", "g", "op", null, 1, 1, 0L, null,
                false, null));
        assertTrue(Storms.storm(failing, null) > 5);
    }

    @Test
    void pipelineRunnerStorm() {
        RedisRuntimeConfig config = cfg("run-h");
        RedisPipelineRunner<Object> happy = Storms.constructing(() -> new RedisPipelineRunner<>(
                config, Storms.deep(RedissonClient.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                "t", "g", List.of((value, ctx, emit) -> emit.emit(value)), List.of()));
        assertTrue(Storms.storm(happy, null) >= 3);
        RedisPipelineRunner<Object> failing = Storms.constructing(() -> new RedisPipelineRunner<>(
                config, Storms.exploding(RedissonClient.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                "t", "g", List.of(), List.of()));
        assertTrue(Storms.storm(failing, null) >= 3);
    }
}
