package io.github.cuihairu.redis.streaming.checkpoint.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the residual {@link RedisCheckpointStorage} branches: blank and
 * non-numeric key suffixes must be skipped, null bucket values must be skipped
 * and {@code cleanupOldCheckpoints} must only count successful deletions.
 */
class RedisCheckpointStorageEdgeCoverageTest {

    @SuppressWarnings("unchecked")
    private RBucket<Checkpoint> bucket() {
        return mock(RBucket.class);
    }

    @Test
    void listCheckpointsSkipsBlankNonNumericSuffixesAndNullValues() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeys(org.mockito.ArgumentMatchers.any(org.redisson.api.options.KeysScanOptions.class))).thenReturn(List.of("p:", "p:12a", "p:3.5", "p:7", "p:9", "p:-4"));

        DefaultCheckpoint kept = new DefaultCheckpoint(7, 700L);
        RBucket<Checkpoint> b7 = bucket();
        RBucket<Checkpoint> b9 = bucket();
        when(redisson.<Checkpoint>getBucket("p:7")).thenReturn(b7);
        when(redisson.<Checkpoint>getBucket("p:9")).thenReturn(b9);
        when(b7.get()).thenReturn(kept);
        when(b9.get()).thenReturn(null);

        RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, "p:");

        List<Checkpoint> listed = storage.listCheckpoints(10);
        assertEquals(List.of(kept), listed,
                "only pure-numeric suffixes with non-null values must be listed");
    }

    @Test
    void cleanupOldCheckpointsCountsOnlySuccessfulDeletes() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeys(org.mockito.ArgumentMatchers.any(org.redisson.api.options.KeysScanOptions.class))).thenReturn(List.of("p:1", "p:2", "p:3"));

        DefaultCheckpoint c1 = new DefaultCheckpoint(1, 1000L);
        DefaultCheckpoint c2 = new DefaultCheckpoint(2, 2000L);
        DefaultCheckpoint c3 = new DefaultCheckpoint(3, 3000L);

        RBucket<Checkpoint> b1 = bucket();
        RBucket<Checkpoint> b2 = bucket();
        RBucket<Checkpoint> b3 = bucket();
        when(redisson.<Checkpoint>getBucket("p:1")).thenReturn(b1);
        when(redisson.<Checkpoint>getBucket("p:2")).thenReturn(b2);
        when(redisson.<Checkpoint>getBucket("p:3")).thenReturn(b3);
        when(b1.get()).thenReturn(c1);
        when(b2.get()).thenReturn(c2);
        when(b3.get()).thenReturn(c3);
        when(b1.delete()).thenReturn(true);
        when(b2.delete()).thenReturn(false);

        RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, "p:");

        int deleted = storage.cleanupOldCheckpoints(1);
        assertTrue(deleted == 1, "only the successful delete must be counted, got " + deleted);
    }
}
