package io.github.cuihairu.redis.streaming.state.redis;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisValueStateTest {

    @Test
    void delegatesValueUpdateAndClearToBucket() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redisson.<String>getBucket("k")).thenReturn(bucket);
        when(bucket.get()).thenReturn("v");

        RedisValueState<String> state = new RedisValueState<>(redisson, "k", String.class);

        assertEquals("k", state.getKey());
        assertEquals("v", state.value());
        verify(bucket).get();

        state.update("v2");
        verify(bucket).set("v2");

        state.clear();
        verify(bucket).delete();
    }

    @Test
    void returnsNullWhenBucketHasNoValue() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redisson.<String>getBucket("k")).thenReturn(bucket);
        when(bucket.get()).thenReturn(null);

        RedisValueState<String> state = new RedisValueState<>(redisson, "k", String.class);
        assertNull(state.value());
    }
}
