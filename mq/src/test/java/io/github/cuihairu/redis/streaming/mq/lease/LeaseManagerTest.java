package io.github.cuihairu.redis.streaming.mq.lease;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes", "unchecked"})
public class LeaseManagerTest {

    @Test
    public void testTryAcquireUsesAtomicSetNxEx() {
        // Regression for MQ-03: acquisition must be a single atomic SET NX EX call. The old
        // setIfAbsent(value) + expire(Duration) pair could leave a TTL-less lease key forever
        // when the process died between the two calls.
        RedissonClient client = mock(RedissonClient.class);
        RBucket bucket = mock(RBucket.class);
        when(client.getBucket(anyString(), any(Codec.class))).thenReturn(bucket);
        when(bucket.setIfAbsent(eq("owner"), any(Duration.class))).thenReturn(true);

        LeaseManager mgr = new LeaseManager(client);
        assertTrue(mgr.tryAcquire("k", "owner", 10));

        verify(bucket).setIfAbsent("owner", Duration.ofSeconds(10));
        verify(bucket, never()).expire(any(Duration.class));
    }

    @Test
    public void testTryAcquireAlreadyOwned() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket bucket = mock(RBucket.class);
        when(client.getBucket(anyString(), any(Codec.class))).thenReturn(bucket);
        when(bucket.setIfAbsent(eq("owner"), any(Duration.class))).thenReturn(false);

        LeaseManager mgr = new LeaseManager(client);
        assertFalse(mgr.tryAcquire("k", "owner", 10));
    }

    @Test
    public void testRenewIfOwnerRunsCompareAndExpireScript() {
        // Regression for MQ-03: renewal must be a Lua compare-and-pexpire, not GET-then-EXPIRE.
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(client.getScript(any(StringCodec.class))).thenReturn(script);
        when(script.eval(any(), anyString(), any(RScript.ReturnType.class), anyList(),
                eq("owner"), eq(10_000L))).thenReturn(1L);

        LeaseManager mgr = new LeaseManager(client);
        assertTrue(mgr.renewIfOwner("k", "owner", 10));

        when(script.eval(any(), anyString(), any(RScript.ReturnType.class), anyList(),
                eq("owner"), eq(10_000L))).thenReturn(0L);
        assertFalse(mgr.renewIfOwner("k", "owner", 10));
    }

    @Test
    public void testReleaseIfOwnerRunsCompareAndDeleteScript() {
        // Regression for MQ-03: release must be a Lua compare-and-delete. The old GET-then-DELETE
        // could delete a successor's freshly acquired lease after the key expired in between.
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(client.getScript(any(StringCodec.class))).thenReturn(script);
        when(script.eval(any(), anyString(), any(RScript.ReturnType.class), anyList(), eq("owner")))
                .thenReturn(1L);

        LeaseManager mgr = new LeaseManager(client);
        assertDoesNotThrow(() -> mgr.releaseIfOwner("k", "owner"));

        ArgumentCaptor<String> lua = ArgumentCaptor.forClass(String.class);
        verify(script).eval(any(), lua.capture(), eq(RScript.ReturnType.LONG), anyList(), eq("owner"));
        assertTrue(lua.getValue().contains("== ARGV[1]"), "script must compare the owner value");
        assertTrue(lua.getValue().contains("'del'"), "script must delete only on match");
    }

    @Test
    public void testIsOwner() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket bucket = mock(RBucket.class);
        when(client.getBucket(anyString(), any(Codec.class))).thenReturn(bucket);
        when(bucket.get()).thenReturn("owner");

        LeaseManager mgr = new LeaseManager(client);
        assertTrue(mgr.isOwner("k", "owner"));
        assertFalse(mgr.isOwner("k", "other"));
    }

    @Test
    public void testExceptionPathsAreBestEffort() {
        RedissonClient client = mock(RedissonClient.class);

        LeaseManager mgr = new LeaseManager(client);

        when(client.getBucket(anyString(), any(Codec.class))).thenThrow(new RuntimeException("boom"));
        assertFalse(mgr.tryAcquire("k", "owner", 10));
        assertFalse(mgr.isOwner("k", "owner"));

        RScript script = mock(RScript.class);
        when(client.getScript(any(StringCodec.class))).thenReturn(script);
        when(script.eval(any(), anyString(), any(RScript.ReturnType.class), anyList(),
                any(Object[].class))).thenThrow(new RuntimeException("boom"));
        assertFalse(mgr.renewIfOwner("k", "owner", 10));
        assertDoesNotThrow(() -> mgr.releaseIfOwner("k", "owner"));
    }
}
