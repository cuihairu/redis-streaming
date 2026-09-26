package io.github.cuihairu.redis.streaming.state.redis;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RBucketAsync;
import org.redisson.api.RList;
import org.redisson.api.RListAsync;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisListStateTest {

    @Test
    void delegatesAddClearAndGet() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("k")).thenReturn(list);
        when(list.toArray()).thenReturn(new Object[]{"a", "b"});

        RedisListState<String> state = new RedisListState<>(redisson, "k", String.class);

        state.add("x");
        verify(list).add("x");

        assertEquals(List.of("a", "b"), state.get());

        state.clear();
        verify(list).clear();
        assertEquals("k", state.getKey());
    }

    /**
     * B-37-era test pinned the defective behavior (clear() then per-element add on a
     * live key). update must now replace the list atomically in one REDIS_WRITE_ATOMIC
     * batch, so a mid-update failure can no longer leave the key emptied or half-written.
     */
    @Test
    void updateReplacesAtomicallyViaWriteAtomicBatch() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBatch batch = mock(RBatch.class);
        @SuppressWarnings("unchecked")
        RBucketAsync<String> bucket = mock(RBucketAsync.class);
        @SuppressWarnings("unchecked")
        RListAsync<String> list = mock(RListAsync.class);
        @SuppressWarnings("unchecked")
        RList<String> legacyList = mock(RList.class);
        when(redisson.createBatch(any(BatchOptions.class))).thenReturn(batch);
        when(batch.<String>getBucket("k")).thenReturn(bucket);
        when(batch.<String>getList("k")).thenReturn(list);
        // stubbed only so the pre-fix clear+add path runs to the verification below
        when(redisson.<String>getList("k")).thenReturn(legacyList);

        RedisListState<String> state = new RedisListState<>(redisson, "k", String.class);
        state.update(List.of("a", "b", "c"));

        verify(redisson).createBatch(argThat(opts ->
                opts.getExecutionMode() == BatchOptions.ExecutionMode.REDIS_WRITE_ATOMIC));
        InOrder inOrder = inOrder(bucket, list, batch);
        inOrder.verify(bucket).deleteAsync();
        inOrder.verify(list).addAllAsync(List.of("a", "b", "c"));
        inOrder.verify(batch).execute();
    }

    @Test
    void emptyUpdateStillDeletesAtomically() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBatch batch = mock(RBatch.class);
        @SuppressWarnings("unchecked")
        RBucketAsync<String> bucket = mock(RBucketAsync.class);
        @SuppressWarnings("unchecked")
        RListAsync<String> list = mock(RListAsync.class);
        @SuppressWarnings("unchecked")
        RList<String> legacyList = mock(RList.class);
        when(redisson.createBatch(any(BatchOptions.class))).thenReturn(batch);
        when(batch.<String>getBucket("k")).thenReturn(bucket);
        when(batch.<String>getList("k")).thenReturn(list);
        when(redisson.<String>getList("k")).thenReturn(legacyList);

        RedisListState<String> state = new RedisListState<>(redisson, "k", String.class);
        state.update(List.of());

        inOrder(bucket, list, batch).verify(bucket).deleteAsync();
        verify(list, never()).addAllAsync(any());
        verify(batch).execute();
    }

    @Test
    void addAllAddsEachElement() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("k")).thenReturn(list);

        RedisListState<String> state = new RedisListState<>(redisson, "k", String.class);
        state.addAll(List.of("a", "b"));

        verify(list).add("a");
        verify(list).add("b");
    }
}
