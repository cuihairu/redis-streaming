package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the blank-key-prefix fallback branches of the Redis-backed rate
 * limiters: a null or whitespace prefix must fall back to the documented
 * default prefix while keeping the limiter functional.
 */
class RedisRateLimiterBlankKeyPrefixCoverageTest {

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Object>> keysCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }

    @Test
    void tokenBucketFallsBackToDefaultPrefixForBlankKey() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        doReturn(Boolean.TRUE).when(script)
                .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(), any(), any(), any());

        RedisTokenBucketRateLimiter rl = new RedisTokenBucketRateLimiter(redisson, "   ", 10.0, 5.0);
        assertTrue(rl.allowAt("userA", 2000));

        ArgumentCaptor<List<Object>> keys = keysCaptor();
        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.BOOLEAN),
                keys.capture(), any(), any(), any(), any());
        assertEquals(List.of("streaming:tb:{userA}:tb"), keys.getValue());
    }

    @Test
    void slidingWindowFallsBackToDefaultPrefixForNullKey() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        doReturn(Boolean.TRUE).when(script)
                .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(), any(), any());

        RedisSlidingWindowRateLimiter rl = new RedisSlidingWindowRateLimiter(redisson, null, 1000L, 5);
        assertTrue(rl.allowAt("userA", 2000));

        ArgumentCaptor<List<Object>> keys = keysCaptor();
        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.BOOLEAN),
                keys.capture(), any(), any(), any());
        assertEquals(List.of("streaming:rl:{userA}", "streaming:rl:{userA}:seq"), keys.getValue());
    }

    @Test
    void slidingWindowFallsBackToDefaultPrefixForBlankKey() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        doReturn(Boolean.TRUE).when(script)
                .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(), any(), any());

        RedisSlidingWindowRateLimiter rl = new RedisSlidingWindowRateLimiter(redisson, "  ", 1000L, 5);
        assertTrue(rl.allowAt("userB", 2000));

        ArgumentCaptor<List<Object>> keys = keysCaptor();
        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.BOOLEAN),
                keys.capture(), any(), any(), any());
        assertEquals(List.of("streaming:rl:{userB}", "streaming:rl:{userB}:seq"), keys.getValue());
    }
}
