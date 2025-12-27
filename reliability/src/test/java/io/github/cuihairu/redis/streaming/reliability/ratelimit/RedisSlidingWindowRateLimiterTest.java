package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisSlidingWindowRateLimiterTest {

    @Test
    void constructorValidations() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(mock(RScript.class));

        assertThrows(NullPointerException.class, () -> new RedisSlidingWindowRateLimiter(null, "p", 1000, 1));
        assertThrows(IllegalArgumentException.class, () -> new RedisSlidingWindowRateLimiter(redisson, "p", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RedisSlidingWindowRateLimiter(redisson, "p", 1000, 0));
    }

    @Test
    void allowAtBuildsExpectedKeysAndArgsAndReturnsTrue() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        doReturn(Boolean.TRUE).when(script)
                .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(), any(), any());

        RedisSlidingWindowRateLimiter rl = new RedisSlidingWindowRateLimiter(redisson, null, 1000, 3);
        assertTrue(rl.allowAt("userA", 1234));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.BOOLEAN),
                keysCaptor.capture(), eq("1000"), eq("3"), eq("1234"));

        List<Object> keys = keysCaptor.getValue();
        assertEquals(List.of("streaming:rl:{userA}", "streaming:rl:{userA}:seq"), keys);

        // varargs are passed as Strings
    }

    @Test
    void returnsFalseWhenScriptReturnsNullOrFalse() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        doReturn(null, Boolean.FALSE).when(script)
                .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(), any(), any());

        RedisSlidingWindowRateLimiter rl = new RedisSlidingWindowRateLimiter(redisson, "pfx", 1000, 1);
        assertFalse(rl.allowAt("k", 1));
        assertFalse(rl.allowAt("k", 1));
    }

    @Test
    void rejectsNullKey() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(mock(RScript.class));
        RedisSlidingWindowRateLimiter rl = new RedisSlidingWindowRateLimiter(redisson, "pfx", 1000, 1);
        assertThrows(NullPointerException.class, () -> rl.allowAt(null, 1));
    }
}
