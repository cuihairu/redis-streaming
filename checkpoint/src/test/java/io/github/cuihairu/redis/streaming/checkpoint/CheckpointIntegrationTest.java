package io.github.cuihairu.redis.streaming.checkpoint;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointCoordinator;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Checkpoint module
 */
@Slf4j
@Tag("integration")
public class CheckpointIntegrationTest {

    private RedissonClient redisson;
    private RedisCheckpointStorage storage;
    private RedisCheckpointCoordinator coordinator;

    @BeforeEach
    public void setUp() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        redisson = Redisson.create(config);

        storage = new RedisCheckpointStorage(redisson, "test:checkpoint:");

        // Clean up any existing test data before each test
        try {
            redisson.getKeys().deleteByPattern("test:checkpoint:*");
        } catch (Exception e) {
            log.warn("Failed to clean up test data", e);
        }

        coordinator = new RedisCheckpointCoordinator(storage, 2); // Require 2 task acks
    }

    @AfterEach
    public void tearDown() {
        if (coordinator != null) {
            coordinator.close();
        }
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    @Test
    public void testTriggerCheckpoint() {
        log.info("Testing trigger checkpoint");

        long checkpointId = coordinator.triggerCheckpoint();
        assertTrue(checkpointId >= 0);

        Checkpoint checkpoint = coordinator.getCheckpoint(checkpointId);
        assertNotNull(checkpoint);
        assertEquals(checkpointId, checkpoint.getCheckpointId());
        assertFalse(checkpoint.isCompleted());

        log.info("Triggered checkpoint: {}", checkpoint);
    }

    @Test
    public void testAcknowledgeCheckpoint() {
        log.info("Testing acknowledge checkpoint");

        long checkpointId = coordinator.triggerCheckpoint();

        // Acknowledge from task 1
        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");

        Checkpoint checkpoint = coordinator.getCheckpoint(checkpointId);
        assertFalse(checkpoint.isCompleted()); // Need 2 acks

        // Acknowledge from task 2
        coordinator.acknowledgeCheckpoint(checkpointId, "task-2");

        // Should be completed now
        checkpoint = coordinator.getCheckpoint(checkpointId);
        assertTrue(checkpoint.isCompleted());

        log.info("Checkpoint completed after {} acks", 2);
    }

    @Test
    public void testStateSnapshot() throws Exception {
        log.info("Testing state snapshot");

        // Create checkpoint with state
        DefaultCheckpoint checkpoint = new DefaultCheckpoint(100, System.currentTimeMillis());
        Checkpoint.StateSnapshot snapshot = checkpoint.getStateSnapshot();

        snapshot.putState("counter", 42);
        snapshot.putState("name", "test");
        snapshot.putState("data", new byte[]{1, 2, 3});

        storage.storeCheckpoint(checkpoint);

        // Load and verify
        Checkpoint loaded = storage.loadCheckpoint(100);
        assertNotNull(loaded);

        Checkpoint.StateSnapshot loadedSnapshot = loaded.getStateSnapshot();
        assertEquals(42, (Integer) loadedSnapshot.getState("counter"));
        assertEquals("test", loadedSnapshot.getState("name"));
        assertArrayEquals(new byte[]{1, 2, 3}, loadedSnapshot.getState("data"));

        log.info("State snapshot saved and restored successfully");
    }

    @Test
    public void testRestoreFromCheckpoint() {
        log.info("Testing restore from checkpoint");

        // Create and complete a checkpoint
        long checkpointId = coordinator.triggerCheckpoint();
        Checkpoint checkpoint = coordinator.getCheckpoint(checkpointId);

        // Add state
        checkpoint.getStateSnapshot().putState("restored-value", "hello");
        try {
            storage.storeCheckpoint(checkpoint);
        } catch (Exception e) {
            fail("Failed to store checkpoint: " + e.getMessage());
        }

        checkpoint.markCompleted();

        // Restore
        coordinator.restoreFromCheckpoint(checkpointId);

        log.info("Restored from checkpoint {}", checkpointId);
    }

    @Test
    public void testGetLatestCheckpoint() {
        log.info("Testing get latest checkpoint");

        // Trigger multiple checkpoints
        long id1 = coordinator.triggerCheckpoint();
        try {
            Thread.sleep(10); // Ensure different timestamps
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long id2 = coordinator.triggerCheckpoint();

        Checkpoint latest = coordinator.getLatestCheckpoint();
        assertNotNull(latest);
        assertEquals(id2, latest.getCheckpointId());

        log.info("Latest checkpoint ID: {}", latest.getCheckpointId());
    }

    @Test
    public void testCleanupOldCheckpoints() {
        log.info("Testing cleanup old checkpoints");

        // Clean up any existing checkpoints first
        coordinator.cleanupOldCheckpoints(0);

        // Create 5 checkpoints
        for (int i = 0; i < 5; i++) {
            coordinator.triggerCheckpoint();
        }

        // Cleanup, keeping only 2
        int deleted = coordinator.cleanupOldCheckpoints(2);
        assertEquals(3, deleted);

        log.info("Cleaned up {} old checkpoints", deleted);
    }
}