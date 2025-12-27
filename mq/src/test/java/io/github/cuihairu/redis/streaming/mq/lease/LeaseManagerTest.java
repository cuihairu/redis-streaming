package io.github.cuihairu.redis.streaming.mq.lease;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes", "unchecked"})
public class LeaseManagerTest {

    @Test
    public void testTryAcquireSuccessSetsTtl() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket bucket = mock(RBucket.class);
        when(client.getBucket(anyString(), any(Codec.class))).thenReturn(bucket);
        when(bucket.setIfAbsent("owner")).thenReturn(true);
        when(bucket.expire(any(java.time.Duration.class))).thenReturn(true);

        LeaseManager mgr = new LeaseManager(client);
        assertTrue(mgr.tryAcquire("k", "owner", 10));

        verify(bucket, never()).delete();
    }

    @Test
    public void testTryAcquireCleansUpWhenTtlFails() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket bucket = mock(RBucket.class);
        when(client.getBucket(anyString(), any(Codec.class))).thenReturn(bucket);
        when(bucket.setIfAbsent("owner")).thenReturn(true);
        when(bucket.expire(any(java.time.Duration.class))).thenReturn(false);

        LeaseManager mgr = new LeaseManager(client);
        assertFalse(mgr.tryAcquire("k", "owner", 10));

        verify(bucket).delete();
    }

    @Test
    public void testTryAcquireAlreadyOwned() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket bucket = mock(RBucket.class);
        when(client.getBucket(anyString(), any(Codec.class))).thenReturn(bucket);
        when(bucket.setIfAbsent("owner")).thenReturn(false);

        LeaseManager mgr = new LeaseManager(client);
        assertFalse(mgr.tryAcquire("k", "owner", 10));

        verify(bucket, never()).expire(any(java.time.Duration.class));
        verify(bucket, never()).delete();
    }

    @Test
    public void testRenewIfOwner() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket bucket = mock(RBucket.class);
        when(client.getBucket(anyString(), any(Codec.class))).thenReturn(bucket);
        when(bucket.get()).thenReturn("owner");
        when(bucket.expire(any(java.time.Duration.class))).thenReturn(true);

        LeaseManager mgr = new LeaseManager(client);
        assertTrue(mgr.renewIfOwner("k", "owner", 10));

        when(bucket.get()).thenReturn("other");
        assertFalse(mgr.renewIfOwner("k", "owner", 10));
    }

    @Test
    public void testIsOwnerAndRelease() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket bucket = mock(RBucket.class);
        when(client.getBucket(anyString(), any(Codec.class))).thenReturn(bucket);

        when(bucket.get()).thenReturn("owner");
        LeaseManager mgr = new LeaseManager(client);
        assertTrue(mgr.isOwner("k", "owner"));
        mgr.releaseIfOwner("k", "owner");
        verify(bucket).delete();

        reset(bucket);
        when(client.getBucket(anyString(), any(Codec.class))).thenReturn(bucket);
        when(bucket.get()).thenReturn("other");
        assertFalse(mgr.isOwner("k", "owner"));
        mgr.releaseIfOwner("k", "owner");
        verify(bucket, never()).delete();
    }
}
