package io.github.cuihairu.redis.streaming.checkpoint.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for B-14 and B-15 in {@link RedisCheckpointStorage}.
 *
 * <p>B-14: a checkpoint written by triggerCheckpoint but never completed (crash/timeout
 * in between) must never be served as the recovery point, and cleanup must evict it
 * before displacing older completed checkpoints.
 *
 * <p>B-15: listCheckpoints scanned the entire Redis keyspace ({@code KEYS *}) instead
 * of just the storage's own prefix.
 */
class RedisCheckpointStorageRecoveryFilterTest {

    @SuppressWarnings("unchecked")
    private RBucket<Checkpoint> bucket(Checkpoint checkpoint) {
        RBucket<Checkpoint> bucket = mock(RBucket.class);
        when(bucket.get()).thenReturn(checkpoint);
        when(bucket.delete()).thenReturn(true);
        return bucket;
    }

    private RKeys stubKeys(RedissonClient redisson, String prefix, String... keys) {
        RKeys keysMock = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keysMock);
        when(keysMock.getKeys(org.mockito.ArgumentMatchers.any(org.redisson.api.options.KeysScanOptions.class)))
                .thenReturn(List.of(keys));
        return keysMock;
    }

    @Test
    void getLatestCheckpointSkipsIncomplete() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        stubKeys(redisson, "p:", "p:1", "p:2");

        DefaultCheckpoint older = new DefaultCheckpoint(1, 1000L);
        older.markCompleted();
        DefaultCheckpoint newest = new DefaultCheckpoint(2, 2000L); // triggered, never completed

        RBucket<Checkpoint> b1 = bucket(older);
        RBucket<Checkpoint> b2 = bucket(newest);
        when(redisson.<Checkpoint>getBucket("p:1")).thenReturn(b1);
        when(redisson.<Checkpoint>getBucket("p:2")).thenReturn(b2);

        RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, "p:");

        Checkpoint latest = storage.getLatestCheckpoint();
        assertEquals(1, latest.getCheckpointId(),
                "latest must be the newest *completed* checkpoint (old code: served the incomplete id 2)");
    }

    @Test
    void latestIsNullWhenOnlyIncompleteCheckpointsExist() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        stubKeys(redisson, "p:", "p:5");

        RBucket<Checkpoint> b5 = bucket(new DefaultCheckpoint(5, 3000L));
        when(redisson.<Checkpoint>getBucket("p:5")).thenReturn(b5);

        RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, "p:");

        assertNull(storage.getLatestCheckpoint(),
                "an all-incomplete history has no recovery point");
    }

    @Test
    void cleanupEvictsIncompleteBeforeDisplacingCompleted() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        stubKeys(redisson, "p:", "p:1", "p:2", "p:3");

        DefaultCheckpoint c1 = new DefaultCheckpoint(1, 1000L);
        c1.markCompleted();
        DefaultCheckpoint c2 = new DefaultCheckpoint(2, 2000L);
        c2.markCompleted();
        DefaultCheckpoint c3 = new DefaultCheckpoint(3, 3000L); // newest, but incomplete

        RBucket<Checkpoint> b1 = bucket(c1);
        RBucket<Checkpoint> b2 = bucket(c2);
        RBucket<Checkpoint> b3 = bucket(c3);
        when(redisson.<Checkpoint>getBucket("p:1")).thenReturn(b1);
        when(redisson.<Checkpoint>getBucket("p:2")).thenReturn(b2);
        when(redisson.<Checkpoint>getBucket("p:3")).thenReturn(b3);

        RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, "p:");

        int deleted = storage.cleanupOldCheckpoints(2);
        assertEquals(1, deleted);
        verify(b3).delete();
        verify(b1, never()).delete();
        verify(b2, never()).delete();
    }

    @Test
    void listCheckpointsScansOnlyTheStoragePrefix() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RKeys keys = stubKeys(redisson, "p:", "p:2", "p:1");

        DefaultCheckpoint c1 = new DefaultCheckpoint(1, 1000L);
        DefaultCheckpoint c2 = new DefaultCheckpoint(2, 2000L);
        RBucket<Checkpoint> b1 = bucket(c1);
        RBucket<Checkpoint> b2 = bucket(c2);
        when(redisson.<Checkpoint>getBucket("p:1")).thenReturn(b1);
        when(redisson.<Checkpoint>getBucket("p:2")).thenReturn(b2);

        RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, "p:");

        List<Checkpoint> listed = storage.listCheckpoints(10);
        assertEquals(2, listed.size());
        assertEquals(2, listed.get(0).getCheckpointId());

        // B-15: the full-keyspace KEYS scan must not be used anymore
        verify(keys, never()).getKeys();
    }
}
