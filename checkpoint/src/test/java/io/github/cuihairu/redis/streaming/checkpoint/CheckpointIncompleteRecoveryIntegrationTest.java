package io.github.cuihairu.redis.streaming.checkpoint;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointCoordinator;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for B-14 (real Redis): a checkpoint that was persisted by
 * triggerCheckpoint but never received all acknowledgements (crash or timeout in
 * between) must not be served by getLatestCheckpoint — restarting the job would
 * otherwise resume from a torn snapshot — and cleanup must evict it before
 * displacing older completed checkpoints.
 */
@Tag("integration")
class CheckpointIncompleteRecoveryIntegrationTest {

    private static final String PREFIX = "test:ckptfix:";

    private RedissonClient redisson;
    private RedisCheckpointStorage storage;
    private RedisCheckpointCoordinator coordinator;

    @BeforeEach
    void setUp() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        redisson = Redisson.create(config);
        storage = new RedisCheckpointStorage(redisson, PREFIX);
        try {
            redisson.getKeys().deleteByPattern(PREFIX + "*");
        } catch (Exception ignore) {
        }
        coordinator = new RedisCheckpointCoordinator(storage, 2);
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.close();
        }
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    @Test
    void incompleteCheckpointIsNeverServedAsLatest() throws Exception {
        long id = coordinator.triggerCheckpoint();
        assertTrue(id >= 0);
        // only 1 of the required 2 acks arrives (simulates crash before completion)
        coordinator.acknowledgeCheckpoint(id, "task-1");

        Checkpoint stored = storage.loadCheckpoint(id);
        assertNotNull(stored, "the incomplete checkpoint stays inspectable by id");
        assertFalse(stored.isCompleted());
        assertNull(storage.getLatestCheckpoint(),
                "an incomplete checkpoint must not be the recovery point (old code: returned it)");

        // the second ack completes it and it becomes recoverable
        coordinator.acknowledgeCheckpoint(id, "task-2");
        Checkpoint latest = storage.getLatestCheckpoint();
        assertNotNull(latest);
        assertEquals(id, latest.getCheckpointId());
        assertTrue(latest.isCompleted());
    }

    @Test
    void cleanupEvictsIncompleteBeforeDisplacingOlderCompleted() throws Exception {
        long id1 = coordinator.triggerCheckpoint();
        coordinator.acknowledgeCheckpoint(id1, "task-1");
        coordinator.acknowledgeCheckpoint(id1, "task-2");

        long id2 = coordinator.triggerCheckpoint();
        coordinator.acknowledgeCheckpoint(id2, "task-1");
        coordinator.acknowledgeCheckpoint(id2, "task-2");

        long id3 = coordinator.triggerCheckpoint();
        coordinator.acknowledgeCheckpoint(id3, "task-1"); // left incomplete

        int deleted = coordinator.cleanupOldCheckpoints(2);
        assertEquals(1, deleted,
                "the one survivor to evict is the incomplete newest, not the oldest completed");

        assertNull(storage.loadCheckpoint(id3), "incomplete checkpoint must be evicted first");
        assertNotNull(storage.loadCheckpoint(id1), "older completed checkpoint must be kept");
        Checkpoint latest = storage.getLatestCheckpoint();
        assertNotNull(latest);
        assertEquals(id2, latest.getCheckpointId(),
                "latest recoverable checkpoint must survive cleanup");
    }
}
