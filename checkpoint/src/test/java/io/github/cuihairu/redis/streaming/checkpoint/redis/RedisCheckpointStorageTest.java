package io.github.cuihairu.redis.streaming.checkpoint.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisCheckpointStorageTest {

    @Test
    void storeAndLoadDelegateToBucket() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<Checkpoint> bucket = mock(RBucket.class);
        when(redisson.<Checkpoint>getBucket("p:1")).thenReturn(bucket);

        RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, "p:");
        DefaultCheckpoint checkpoint = new DefaultCheckpoint(1, 1000L);

        storage.storeCheckpoint(checkpoint);
        verify(bucket).set(checkpoint);

        when(bucket.get()).thenReturn(checkpoint);
        assertEquals(checkpoint, storage.loadCheckpoint(1));
    }

    @Test
    void listCheckpointsFiltersByPrefixAndSortsByTimestampDescending() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeys()).thenReturn(java.util.Arrays.asList(null, "other:9", "p:1", "p:2"));

        DefaultCheckpoint c1 = new DefaultCheckpoint(1, 1000L);
        DefaultCheckpoint c2 = new DefaultCheckpoint(2, 2000L);

        @SuppressWarnings("unchecked")
        RBucket<Checkpoint> b1 = mock(RBucket.class);
        @SuppressWarnings("unchecked")
        RBucket<Checkpoint> b2 = mock(RBucket.class);

        when(redisson.<Checkpoint>getBucket("p:1")).thenReturn(b1);
        when(redisson.<Checkpoint>getBucket("p:2")).thenReturn(b2);
        when(b1.get()).thenReturn(c1);
        when(b2.get()).thenReturn(c2);

        RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, "p:");

        List<Checkpoint> listed = storage.listCheckpoints(10);
        assertEquals(2, listed.size());
        assertEquals(2, listed.get(0).getCheckpointId());
        assertEquals(1, listed.get(1).getCheckpointId());

        Checkpoint latest = storage.getLatestCheckpoint();
        assertNotNull(latest);
        assertEquals(2, latest.getCheckpointId());
    }

    @Test
    void cleanupOldCheckpointsDeletesBeyondKeepCount() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeys()).thenReturn(List.of("p:1", "p:2", "p:3"));

        DefaultCheckpoint c1 = new DefaultCheckpoint(1, 1000L);
        DefaultCheckpoint c2 = new DefaultCheckpoint(2, 2000L);
        DefaultCheckpoint c3 = new DefaultCheckpoint(3, 3000L);

        @SuppressWarnings("unchecked")
        RBucket<Checkpoint> b1 = mock(RBucket.class);
        @SuppressWarnings("unchecked")
        RBucket<Checkpoint> b2 = mock(RBucket.class);
        @SuppressWarnings("unchecked")
        RBucket<Checkpoint> b3 = mock(RBucket.class);

        when(redisson.<Checkpoint>getBucket("p:1")).thenReturn(b1);
        when(redisson.<Checkpoint>getBucket("p:2")).thenReturn(b2);
        when(redisson.<Checkpoint>getBucket("p:3")).thenReturn(b3);
        when(b1.get()).thenReturn(c1);
        when(b2.get()).thenReturn(c2);
        when(b3.get()).thenReturn(c3);

        when(b1.delete()).thenReturn(true);
        when(b2.delete()).thenReturn(true);
        when(b3.delete()).thenReturn(true);

        RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, "p:");

        int deleted = storage.cleanupOldCheckpoints(1);
        assertEquals(2, deleted);

        // Keep newest only (c3); delete should be invoked for c2 and c1.
        verify(b3, never()).delete();
        verify(b2).delete();
        verify(b1).delete();
    }
}
