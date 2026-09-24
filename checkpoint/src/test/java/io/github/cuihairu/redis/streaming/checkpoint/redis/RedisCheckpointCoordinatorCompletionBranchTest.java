package io.github.cuihairu.redis.streaming.checkpoint.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import io.github.cuihairu.redis.streaming.checkpoint.storage.CheckpointStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch coverage for {@code RedisCheckpointCoordinator}: constructor counter seeding,
 * acknowledge/completion timeouts, missing checkpoints and storage failures.
 */
class RedisCheckpointCoordinatorCompletionBranchTest {

    @Test
    void ctorSeedsCounterFromLatestCheckpoint() throws Exception {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        when(storage.getLatestCheckpoint()).thenReturn(new DefaultCheckpoint(41, System.currentTimeMillis()));

        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);
        assertEquals(42, coordinator.triggerCheckpoint());
        coordinator.close();
    }

    @Test
    void ctorToleratesStorageFailure() throws Exception {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        when(storage.getLatestCheckpoint()).thenThrow(new IllegalStateException("store down"));

        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);
        assertEquals(0, coordinator.triggerCheckpoint());
        coordinator.close();
    }

    @Test
    void acknowledgeOfUnknownCheckpointIsNoOp() {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);
        assertDoesNotThrow(() -> coordinator.acknowledgeCheckpoint(999, "task"));
        coordinator.close();
    }

    @Test
    void acknowledgeOfExpiredPendingRemovesItOnce() throws Exception {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 2, 30);

        long id = coordinator.triggerCheckpoint();
        Thread.sleep(80);
        coordinator.acknowledgeCheckpoint(id, "t1");
        coordinator.acknowledgeCheckpoint(id, "t2");
        assertEquals(0, coordinator.getPendingCheckpointCount());
        verify(storage, never()).loadCheckpoint(anyLong());
        coordinator.close();
    }

    @Test
    void completeCheckpointIgnoresUnknownId() {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);
        assertDoesNotThrow(() -> coordinator.completeCheckpoint(12345));
        coordinator.close();
    }

    @Test
    void completeCheckpointSkipsExpiredPending() throws Exception {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 2, 30);

        long id = coordinator.triggerCheckpoint();
        Thread.sleep(80);
        coordinator.completeCheckpoint(id);
        // only the initial trigger stored the checkpoint; the expired completion must not re-store
        verify(storage, times(1)).storeCheckpoint(any());
        coordinator.close();
    }

    @Test
    void completeCheckpointWithMissingSnapshotIsTolerated() throws Exception {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        when(storage.getLatestCheckpoint()).thenReturn(null);
        when(storage.loadCheckpoint(anyLong())).thenReturn(null);

        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);
        long id = coordinator.triggerCheckpoint();
        coordinator.acknowledgeCheckpoint(id, "t1");
        verify(storage).loadCheckpoint(id);
        coordinator.close();
    }

    @Test
    void completeCheckpointSwallowsStorageFailure() throws Exception {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        when(storage.loadCheckpoint(anyLong())).thenThrow(new IllegalStateException("load failed"));

        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 2, 10_000);
        long id = coordinator.triggerCheckpoint();
        coordinator.acknowledgeCheckpoint(id, "t1");
        assertDoesNotThrow(() -> coordinator.completeCheckpoint(id));
        coordinator.close();
    }

    @Test
    void completeCheckpointMarksLoadedCheckpointCompleted() throws Exception {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 2, 10_000);
        long id = coordinator.triggerCheckpoint();
        DefaultCheckpoint loaded = new DefaultCheckpoint(id, System.currentTimeMillis());
        when(storage.loadCheckpoint(id)).thenReturn(loaded);

        coordinator.completeCheckpoint(id);
        org.junit.jupiter.api.Assertions.assertTrue(loaded.isCompleted());
        verify(storage).storeCheckpoint(loaded);
        coordinator.close();
    }

    @Test
    void triggerCheckpointRollsBackPendingOnStoreFailure() throws Exception {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        doThrow(new IllegalStateException("store failed")).when(storage).storeCheckpoint(any());

        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);
        assertEquals(-1, coordinator.triggerCheckpoint());
        assertEquals(0, coordinator.getPendingCheckpointCount());
        coordinator.close();
    }

    @Test
    void cleanupWithNonPositiveTimeoutRemovesNothing() {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 0);
        assertEquals(0, coordinator.cleanupExpiredPendingCheckpoints());
        coordinator.close();
    }
}
