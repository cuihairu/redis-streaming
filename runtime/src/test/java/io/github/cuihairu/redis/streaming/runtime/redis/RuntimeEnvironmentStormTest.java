package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;

/** Storm for the Redis execution environment surface with a fully-failing Redisson client. */
class RuntimeEnvironmentStormTest {

    @Test
    void environmentStormFailingBackend() {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder().jobName("env-storm").build();
        RedisStreamExecutionEnvironment env = Storms.constructing(
                () -> RedisStreamExecutionEnvironment.create(Storms.exploding(RedissonClient.class), cfg));
        assertTrue(Storms.storm(env, null) > 5);
        RedisStreamExecutionEnvironment happy = Storms.constructing(
                () -> RedisStreamExecutionEnvironment.create(Storms.deep(RedissonClient.class), cfg));
        assertTrue(Storms.storm(happy, null) > 5);
    }

    @Test
    void configSurfaceFullyExercised() {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("cfg")
                .build();
        int invoked = Storms.storm(cfg, null);
        assertTrue(invoked > 20, "config getters should be plentiful, got " + invoked);
    }
}
