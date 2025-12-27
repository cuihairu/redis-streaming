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

class RedisTokenBucketRateLimiterTest {

    @Test
    void constructorValidations() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(mock(RScript.class));

        assertThrows(NullPointerException.class, () -> new RedisTokenBucketRateLimiter(null, "p", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RedisTokenBucketRateLimiter(redisson, "p", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RedisTokenBucketRateLimiter(redisson, "p", 1, 0));
    }

    @Test
    void allowAtBuildsExpectedKeysAndArgsAndReturnsTrue() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        doReturn(Boolean.TRUE).when(script)
                .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(), any(), any(), any());

        RedisTokenBucketRateLimiter rl = new RedisTokenBucketRateLimiter(redisson, null, 10.0, 5.0);
        assertTrue(rl.allowAt("userA", 2000));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.BOOLEAN),
                keysCaptor.capture(), eq("10.0"), eq(String.valueOf(5.0 / 1000.0d)), eq("2000"), eq("4000"));

        List<Object> keys = keysCaptor.getValue();
        assertEquals(List.of("streaming:tb:{userA}:tb"), keys);

        // varargs are passed as Strings
    }

    @Test
    void returnsFalseWhenScriptReturnsNullOrFalse() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        doReturn(null, Boolean.FALSE).when(script)
                .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(), any(), any(), any());

        RedisTokenBucketRateLimiter rl = new RedisTokenBucketRateLimiter(redisson, "pfx", 1.0, 1.0);
        assertFalse(rl.allowAt("k", 1));
        assertFalse(rl.allowAt("k", 1));
    }

    @Test
    void rejectsNullKey() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(mock(RScript.class));
        RedisTokenBucketRateLimiter rl = new RedisTokenBucketRateLimiter(redisson, "pfx", 1.0, 1.0);
        assertThrows(NullPointerException.class, () -> rl.allowAt(null, 1));
    }
}
