package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * RT-H2 regression at the manager level: {@code triggerCheckpoint} must expose a variant
 * that stores the checkpoint without running the retention sweep inline, so a
 * stop-the-world caller can sweep after resuming the consumers. The explicit
 * {@code cleanupOld()} keeps its exact eviction semantics (trim to checkpointsToKeep,
 * oldest first).
 *
 * <p>Reflection is used for the deferred-cleanup entry points on purpose: on the pre-fix
 * code neither exists, and this test must fail against it instead of not compiling.</p>
 */
class RedisRuntimeCheckpointManagerCleanupDeferralTest {

    @Test
    void deferredTriggerSweepsNothingAndExplicitCleanupOldTrimsOldestFirst() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RedisRuntimeConfig config = RedisRuntimeConfig.builder()
                .jobName("cp-defer-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("defer-state:")
                .checkpointKeyPrefix("defer-cp:")
                .checkpointsToKeep(2)
                .build();
        RedisRuntimeCheckpointManager manager = new RedisRuntimeCheckpointManager(redisson, config);
        RecordingCheckpointStorage storage = new RecordingCheckpointStorage();
        Field storageField = RedisRuntimeCheckpointManager.class.getDeclaredField("storage");
        storageField.setAccessible(true);
        storageField.set(manager, storage);

        Method deferredTrigger = null;
        for (Method m : RedisRuntimeCheckpointManager.class.getDeclaredMethods()) {
            if (m.getName().equals("triggerCheckpoint") && m.getParameterCount() == 4) {
                m.setAccessible(true);
                deferredTrigger = m;
                break;
            }
        }
        assertNotNull(deferredTrigger,
                "RT-H2: triggerCheckpoint has no deferred-cleanup variant, so a "
                        + "stop-the-world caller cannot sweep outside the pause");
        List<RedisRuntimeCheckpointManager.PipelineKey> pipelines =
                List.of(new RedisRuntimeCheckpointManager.PipelineKey("t", "g"));

        Checkpoint cp = (Checkpoint) deferredTrigger.invoke(manager, 1L, pipelines, null, false);
        assertNotNull(cp);
        assertFalse(storage.sweepCalls > 0,
                "storing with cleanup deferred must not sweep (sweeps=" + storage.sweepCalls + ")");

        deferredTrigger.invoke(manager, 2L, pipelines, null, false);
        deferredTrigger.invoke(manager, 3L, pipelines, null, false);
        assertEquals(3, storage.stored.size());
        assertEquals(0, storage.sweepCalls, "still no sweep: it belongs to the explicit call");

        Method cleanupOld = RedisRuntimeCheckpointManager.class.getDeclaredMethod("cleanupOld");
        cleanupOld.setAccessible(true);
        cleanupOld.invoke(manager);

        assertTrue(storage.sweepCalls > 0, "the explicit sweep must list the checkpoints");
        assertEquals(2, storage.stored.size(), "the sweep must trim to checkpointsToKeep");
        assertEquals(Set.of(2L, 3L), storage.stored.keySet(),
                "the oldest checkpoint is evicted first");
    }

    private static final class RecordingCheckpointStorage extends RedisCheckpointStorage {

        private final Map<Long, Checkpoint> stored = new ConcurrentHashMap<>();
        private int sweepCalls;

        private RecordingCheckpointStorage() {
            super(null, "rec:");
        }

        private List<Checkpoint> newestFirst() {
            return stored.values().stream()
                    .sorted(Comparator.comparingLong(Checkpoint::getTimestamp)
                            .thenComparingLong(Checkpoint::getCheckpointId)
                            .reversed())
                    .collect(Collectors.toList());
        }

        @Override
        public void storeCheckpoint(Checkpoint checkpoint) {
            stored.put(checkpoint.getCheckpointId(), checkpoint);
        }

        @Override
        public Checkpoint loadCheckpoint(long checkpointId) {
            return stored.get(checkpointId);
        }

        @Override
        public Checkpoint getLatestCheckpoint() {
            return newestFirst().stream()
                    .filter(Checkpoint::isCompleted)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<Checkpoint> listCheckpoints(int limit) {
            sweepCalls++;
            return newestFirst().stream().limit(limit).collect(Collectors.toList());
        }

        @Override
        public boolean deleteCheckpoint(long checkpointId) {
            return stored.remove(checkpointId) != null;
        }

        @Override
        public int cleanupOldCheckpoints(int keepCount) {
            List<Checkpoint> all = listCheckpoints(Integer.MAX_VALUE);
            int deleted = 0;
            for (int i = keepCount; i < all.size(); i++) {
                if (deleteCheckpoint(all.get(i).getCheckpointId())) {
                    deleted++;
                }
            }
            return deleted;
        }

        @Override
        public void close() {
        }
    }
}
