package io.github.cuihairu.redis.streaming.checkpoint.redis;

import io.github.cuihairu.redis.streaming.checkpoint.storage.CheckpointStorage;
import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Error-path storm for {@link RedisCheckpointCoordinator} against happy and failing storage. */
class CheckpointCoordinatorStormTest {

    @Test
    void coordinatorStormBothWays() {
        RedisCheckpointCoordinator happy = Storms.constructing(
                () -> new RedisCheckpointCoordinator(Storms.deep(CheckpointStorage.class), 2, 60_000L));
        assertTrue(Storms.storm(happy, null) > 3);
        RedisCheckpointCoordinator failing = Storms.constructing(
                () -> new RedisCheckpointCoordinator(Storms.exploding(CheckpointStorage.class), 1, 1_000L));
        assertTrue(Storms.storm(failing, null) > 3);
    }

    @Test
    void redisCheckpointStorageStormBothWays() {
        RedisCheckpointStorage happy = Storms.constructing(
                () -> new RedisCheckpointStorage(Storms.deep(org.redisson.api.RedissonClient.class)));
        assertTrue(Storms.storm(happy, null) > 3);
        RedisCheckpointStorage failing = Storms.constructing(
                () -> new RedisCheckpointStorage(Storms.exploding(org.redisson.api.RedissonClient.class)));
        assertTrue(Storms.storm(failing, null) > 3);
    }
}
