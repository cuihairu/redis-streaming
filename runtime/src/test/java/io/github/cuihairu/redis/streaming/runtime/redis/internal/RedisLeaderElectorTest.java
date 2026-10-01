package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScript;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Leader election and fencing-token primitives of {@link RedisLeaderElector}: lease
 * acquire/renew/release semantics and monotonic epoch tokens.
 */
class RedisLeaderElectorTest {

    private static final String PREFIX = "streaming:runtime";
    private static final String JOB = "job";
    private static final String INSTANCE = "inst-1";

    private RedissonClient redisson;
    private RBucket<String> leaderBucket;
    private RScript script;
    private RAtomicLong fenceCounter;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setUpMocks() {
        redisson = mock(RedissonClient.class);
        leaderBucket = mock(RBucket.class);
        script = mock(RScript.class);
        fenceCounter = mock(RAtomicLong.class);
        when(redisson.getBucket(anyString(), any(StringCodec.class))).thenReturn((RBucket) leaderBucket);
        when(redisson.getScript(any(StringCodec.class))).thenReturn(script);
        when(redisson.getAtomicLong(anyString())).thenReturn(fenceCounter);
    }

    private RedisLeaderElector elector() {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName(JOB)
                .jobInstanceId(INSTANCE)
                .stateKeyPrefix(PREFIX)
                .leaderElectionEnabled(true)
                .leaderLeaseTtl(Duration.ofSeconds(30))
                .leaderRenewInterval(Duration.ofSeconds(10))
                .build();
        return new RedisLeaderElector(redisson, cfg);
    }

    @Test
    void tryAcquireLeadershipSetsLeaseWithTtlWhenKeyIsFree() {
        setUpMocks();
        when(leaderBucket.setIfAbsent(eq(INSTANCE), any(Duration.class))).thenReturn(Boolean.TRUE);

        RedisLeaderElector e = elector();
        assertTrue(e.tryAcquireLeadership());
        verify(leaderBucket).setIfAbsent(eq(INSTANCE), eq(Duration.ofSeconds(30)));
    }

    @Test
    void tryAcquireLeadershipReturnsTrueWhenAlreadyHeldByThisInstance() {
        setUpMocks();
        when(leaderBucket.setIfAbsent(eq(INSTANCE), any(Duration.class))).thenReturn(Boolean.FALSE);
        when(leaderBucket.get()).thenReturn(INSTANCE);

        assertTrue(elector().tryAcquireLeadership());
    }

    @Test
    void tryAcquireLeadershipReturnsFalseWhenHeldByAnotherInstance() {
        setUpMocks();
        when(leaderBucket.setIfAbsent(eq(INSTANCE), any(Duration.class))).thenReturn(Boolean.FALSE);
        when(leaderBucket.get()).thenReturn("other-instance");

        assertFalse(elector().tryAcquireLeadership());
    }

    @Test
    void tryAcquireLeadershipSwallowsRedisErrors() {
        setUpMocks();
        when(leaderBucket.setIfAbsent(eq(INSTANCE), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertFalse(elector().tryAcquireLeadership());
    }

    @Test
    void renewLeadershipRenewsOnlyWhileLeaseIsHeld() {
        setUpMocks();
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                any(List.class), eq(INSTANCE), anyString())).thenReturn(1L);

        assertTrue(elector().renewLeadership());
        verify(script).eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                any(List.class), eq(INSTANCE), eq("30000"));
    }

    @Test
    void renewLeadershipReturnsFalseWhenLeaseWasLost() {
        setUpMocks();
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                any(List.class), eq(INSTANCE), anyString())).thenReturn(0L);

        assertFalse(elector().renewLeadership());
    }

    @Test
    void releaseLeadershipDeletesOnlyWhileLeaseIsHeld() {
        setUpMocks();
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                any(List.class), eq(INSTANCE))).thenReturn(1L);

        assertTrue(elector().releaseLeadership());
    }

    @Test
    void releaseLeadershipReturnsFalseWhenNotHeld() {
        setUpMocks();
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                any(List.class), eq(INSTANCE))).thenReturn(0L);

        assertFalse(elector().releaseLeadership());
    }

    @Test
    void isLeaderComparesLeaseHolderWithThisInstance() {
        setUpMocks();
        RedisLeaderElector e = elector();
        when(leaderBucket.get()).thenReturn(INSTANCE);
        assertTrue(e.isLeader());
        when(leaderBucket.get()).thenReturn("other-instance");
        assertFalse(e.isLeader());
    }

    @Test
    void currentLeaderReadsLeaseHolder() {
        setUpMocks();
        when(leaderBucket.get()).thenReturn("other-instance");
        assertEquals("other-instance", elector().currentLeader());
    }

    @Test
    void nextFencingTokenAllocatesMonotonicEpochAndCachesIt() {
        setUpMocks();
        when(fenceCounter.incrementAndGet()).thenReturn(7L);

        RedisLeaderElector e = elector();
        assertEquals(7L, e.nextFencingToken());
        assertEquals(7L, e.currentFencingToken());
    }

    @Test
    void currentFencingTokenFallsBackToCounterWhenNoEpochAllocated() {
        setUpMocks();
        when(fenceCounter.get()).thenReturn(4L);

        assertEquals(4L, elector().currentFencingToken());
    }

    @Test
    void nextFencingTokenDegradesToZeroWhenRedisFails() {
        setUpMocks();
        when(fenceCounter.incrementAndGet()).thenThrow(new RuntimeException("redis down"));

        RedisLeaderElector e = elector();
        assertEquals(0L, e.nextFencingToken());
        assertEquals(0L, e.currentFencingToken());
    }

    @Test
    void renewLeadershipSwallowsRedisErrors() {
        setUpMocks();
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                any(List.class), eq(INSTANCE), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        assertFalse(elector().renewLeadership());
    }

    @Test
    void releaseLeadershipSwallowsRedisErrors() {
        setUpMocks();
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                any(List.class), eq(INSTANCE)))
                .thenThrow(new RuntimeException("redis down"));

        assertFalse(elector().releaseLeadership());
    }

    @Test
    void currentLeaderReturnsNullWhenRedisFails() {
        setUpMocks();
        when(leaderBucket.get()).thenThrow(new RuntimeException("redis down"));

        assertNull(elector().currentLeader());
    }

    @Test
    void isLeaderReturnsFalseWhenRedisFails() {
        setUpMocks();
        when(leaderBucket.get()).thenThrow(new RuntimeException("redis down"));

        assertFalse(elector().isLeader());
    }

    @Test
    void currentFencingTokenReturnsZeroWhenCounterReadFails() {
        setUpMocks();
        when(fenceCounter.get()).thenThrow(new RuntimeException("redis down"));

        assertEquals(0L, elector().currentFencingToken());
    }

    @Test
    void keysAreNamespacedByPrefixAndJob() {
        setUpMocks();
        RedisLeaderElector e = elector();
        assertEquals(PREFIX + ":" + JOB + ":leader", e.leaderKey());
        assertEquals(PREFIX + ":" + JOB + ":fence", e.fenceKey());
    }

    @Test
    void acquireDoesNotTouchFencingCounter() {
        setUpMocks();
        when(leaderBucket.setIfAbsent(eq(INSTANCE), any(Duration.class))).thenReturn(Boolean.TRUE);

        elector().tryAcquireLeadership();
        verify(fenceCounter, never()).incrementAndGet();
    }
}
