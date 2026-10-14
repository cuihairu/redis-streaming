package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit lattice for the cross-instance job control gate: two TTL lease keys per job, the same
 * two-level order as {@link CheckpointGate} (exclusive first, full silence second, reverse
 * release, {@code silence ⇒ exclusive}), a fail-fast refusal when another instance holds the
 * lease or Redis is unreachable, and a cross-instance contention storm that must never overlap.
 *
 * <p>The Redis side is an in-memory key store driven by the same Lua-shaped semantics the gate
 * emits — GET/SET-NX/PX for acquire, compare-and-delete for release, compare-and-pexpire for
 * renew — so the mutual exclusion under test is the real one, only the transport is mocked.
 */
class JobControlGateTest {

    private static final String EXCL = "streaming:p:job:ctl:excl";
    private static final String SILENCE = "streaming:p:job:ctl:silence";
    private static final long TTL_MS = 60_000;

    private RedissonClient redisson;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<String> ttls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> store = new ConcurrentHashMap<>();
    private final AtomicBoolean blockAcquire = new AtomicBoolean(false);
    private final AtomicBoolean redisUnreachable = new AtomicBoolean(false);

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(any(Codec.class))).thenReturn(script);
        // A lone any() in the varargs position matches ANY arity, and Mockito lets the
        // last-registered matching stub win — so the wide stub must come first and the exact
        // two-value one second, or the two-value calls land on the wide stub (or vice versa)
        // and the recorded probe sequence no longer matches what the gate really sent.
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                        anyList(), any()))
                .thenAnswer(this::dispatch);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                        anyList(), any(), any()))
                .thenAnswer(this::dispatch);
    }

    /** Simulates the three lease Lua scripts against the in-memory key store, atomically per key. */
    private synchronized long dispatch(InvocationOnMock inv) {
        Object[] args = inv.getArguments();
        String lua = String.valueOf(args[1]);
        String key = ((List<?>) args[3]).get(0).toString();
        String holder = args.length > 4 ? String.valueOf(args[4]) : null;
        boolean acquire = lua.contains("'SET'");
        boolean release = !acquire && lua.contains("'DEL'");
        calls.add((acquire ? "acquire:" : release ? "release:" : "renew:") + key);
        // Real Redis rejects a TTL-less acquire or renew with
        // "Lua redis lib command arguments must be strings or integers". Mirror that so a
        // call that forgets to send ARGV[2] fails here instead of reading as a false refusal.
        if (!release && args.length <= 5) {
            throw new AssertionError("acquire/renew must send the TTL as ARGV[2], lua=" + lua);
        }
        String ttl = args.length > 5 ? String.valueOf(args[5]) : null;
        if (ttl != null) {
            ttls.add(ttl);
        }
        if (redisUnreachable.get()) {
            throw new RuntimeException("connection refused");
        }
        if (blockAcquire.get() && acquire) {
            return 0L;
        }
        String current = store.get(key);
        if (!acquire && !release) {
            return current != null && current.equals(holder) ? 1L : 0L;
        }
        if (release) {
            if (current != null && current.equals(holder)) {
                store.remove(key);
                return 1L;
            }
            return 0L;
        }
        if (current == null) {
            store.put(key, holder);
            return 1L;
        }
        return current.equals(holder) ? 1L : 0L;
    }

    private JobControlGate gate(String instanceId) {
        return new JobControlGate(redisson, EXCL, SILENCE, instanceId, TTL_MS, 0);
    }

    @Test
    void beginAcquiresBothLevelsAndEndReleasesThemInReverse() {
        JobControlGate gate = gate("inst-1");

        assertTrue(gate.tryBeginControlOp());
        assertTrue(gate.isExclusiveHeld(), "a held exclusive lease must be observable");
        assertTrue(gate.isFullSilenceHeld(), "both levels are taken for a control operation");
        assertFalse(gate.tryBeginControlOp(), "a second control operation must be refused");
        assertEquals(List.of("acquire:" + EXCL, "acquire:" + SILENCE, "acquire:" + EXCL), calls,
                "the second begin must probe only the exclusive key and be refused there");
        assertEquals(List.of(String.valueOf(TTL_MS), String.valueOf(TTL_MS),
                        String.valueOf(TTL_MS)), ttls,
                "every acquire attempt must send the configured TTL, even the refused one");

        gate.endControlOp();
        assertEquals(List.of("acquire:" + EXCL, "acquire:" + SILENCE, "acquire:" + EXCL,
                        "release:" + SILENCE, "release:" + EXCL), calls,
                "release must run in reverse acquisition order");
        assertFalse(gate.isExclusiveHeld());
        assertFalse(gate.isFullSilenceHeld());
        assertTrue(store.isEmpty(), "the gate must leave no lease behind");
    }

    @Test
    void beginIsRefusedWhileAnotherInstanceOwnsTheExclusiveLease() {
        store.put(EXCL, "inst-2:7");
        JobControlGate gate = gate("inst-1");

        assertFalse(gate.tryBeginControlOp());
        assertEquals(List.of("acquire:" + EXCL), calls,
                "the silence key must not even be attempted after an exclusive refusal");
        assertFalse(gate.isExclusiveHeld());
        assertFalse(gate.isFullSilenceHeld());
        assertEquals("inst-2:7", store.get(EXCL), "a refusal must not touch the other holder's lease");

        gate.endControlOp();
        assertEquals(1, calls.size(), "a refused begin must release nothing");
    }

    @Test
    void beginIsRefusedWhenRedisIsUnreachable() {
        redisUnreachable.set(true);
        try {
            JobControlGate gate = gate("inst-1");

            // fail-safe: no reachable lease means no control operation, never a torn one
            assertFalse(gate.tryBeginControlOp());
            assertFalse(gate.isExclusiveHeld());
            assertFalse(gate.isFullSilenceHeld());
            assertEquals(List.of("acquire:" + EXCL), calls, "the refusal must happen on the first probe");
        } finally {
            redisUnreachable.set(false);
        }
    }

    /**
     * Defensive branch: if the {@code silence ⇒ exclusive} invariant is broken (another
     * instance strands a silence lease without the exclusive one), a refused begin must still
     * hand back the exclusive lease it won — a stranded level would wedge every future control
     * operation until the TTL expires.
     */
    @Test
    void failedSilenceAcquisitionReleasesTheWonExclusiveLease() {
        store.put(SILENCE, "inst-2:3");
        JobControlGate gate = gate("inst-1");

        assertFalse(gate.tryBeginControlOp());
        assertEquals(List.of("acquire:" + EXCL, "acquire:" + SILENCE, "release:" + EXCL), calls,
                "the won exclusive lease must be handed back");
        assertFalse(store.containsKey(EXCL));
        assertEquals("inst-2:3", store.get(SILENCE), "another holder's silence lease must stay");
        assertFalse(gate.isExclusiveHeld());
        assertFalse(gate.isFullSilenceHeld());
    }

    @Test
    void waitAcquiresOnceTheOtherHolderReleases() {
        JobControlGate gate = gate("inst-1");
        blockAcquire.set(true);

        assertFalse(gate.tryBeginControlOp(), "a zero wait budget must fail fast");
        assertEquals(List.of("acquire:" + EXCL), calls);

        blockAcquire.set(false);
        assertTrue(gate.tryBeginControlOp(5_000),
                "a positive wait budget must poll until the other holder releases");
        gate.endControlOp();
    }

    @Test
    void waitTimesOutWhenTheOtherHolderNeverReleases() {
        JobControlGate gate = gate("inst-1");
        blockAcquire.set(true);

        assertFalse(gate.tryBeginControlOp(200));
        long probes = calls.stream().filter(c -> c.equals("acquire:" + EXCL)).count();
        assertTrue(probes >= 2,
                "a positive wait budget must retry, not just probe once, probes=" + probes);
        assertFalse(gate.isExclusiveHeld());
    }

    @Test
    void renewHeldNowRefreshesBothHeldLeases() {
        JobControlGate gate = gate("inst-1");
        assertTrue(gate.tryBeginControlOp());

        gate.renewHeldNow();

        assertEquals(List.of("acquire:" + EXCL, "acquire:" + SILENCE,
                        "renew:" + EXCL, "renew:" + SILENCE), calls);
        assertEquals(4, ttls.size(), "acquire and renew must both send the TTL");
        assertTrue(ttls.stream().allMatch(String.valueOf(TTL_MS)::equals),
                "every lease write must carry the configured TTL, ttls=" + ttls);
        gate.endControlOp();
    }

    @Test
    void renewIsRefusedForAStolenLease() {
        JobControlGate gate = gate("inst-1");
        assertTrue(gate.tryBeginControlOp());

        // the TTL expired and another instance took over while this one was still talking
        store.put(EXCL, "inst-9:1");
        store.put(SILENCE, "inst-9:1");

        gate.renewHeldNow();

        assertEquals(List.of("acquire:" + EXCL, "acquire:" + SILENCE,
                        "renew:" + EXCL, "renew:" + SILENCE), calls);
        assertEquals("inst-9:1", store.get(EXCL),
                "a stale renew must not extend a lease it does not hold");
        assertEquals("inst-9:1", store.get(SILENCE));
        // the owner-side flag is not cleared here: only the owner's endControlOp closes the
        // handle, and its compare-and-delete then frees nothing on the new holder's lease
        assertTrue(gate.isExclusiveHeld());
        gate.endControlOp();
        assertEquals("inst-9:1", store.get(EXCL),
                "a stale end must not free the lease the taker already owns");
    }

    /**
     * A renewal transport failure must not escape onto the renewal daemon thread. The daemon
     * aborts at the first key that fails (no point in renewing silence after exclusive could
     * not be renewed) and lets the TTL bound the outage — the same bound the election lease
     * already accepts.
     */
    @Test
    void renewFailureIsSwallowedAndTheLeaseStaysUnextended() {
        JobControlGate gate = gate("inst-1");
        assertTrue(gate.tryBeginControlOp());

        redisUnreachable.set(true);
        try {
            gate.renewHeldNow();
        } finally {
            redisUnreachable.set(false);
        }

        assertEquals(List.of("acquire:" + EXCL, "acquire:" + SILENCE,
                        "renew:" + EXCL), calls,
                "the daemon aborts at the first key that fails");
        assertTrue(gate.isExclusiveHeld(), "the handle stays open across a renewal outage");
        gate.endControlOp();
    }

    /** A release transport failure must not turn into an exception on the control-op path. */
    @Test
    void releaseFailureIsSwallowedAndTheHandleStillCloses() {
        JobControlGate gate = gate("inst-1");
        assertTrue(gate.tryBeginControlOp());

        redisUnreachable.set(true);
        try {
            gate.endControlOp();
        } finally {
            redisUnreachable.set(false);
        }

        assertFalse(gate.isExclusiveHeld(), "the handle must close even when Redis is gone");
        assertFalse(gate.isFullSilenceHeld());
        // the lease stays in Redis and self-heals by TTL: exactly the crashed-holder case
        assertTrue(store.containsKey(EXCL) && store.containsKey(SILENCE));
    }

    @Test
    void renewalDaemonKeepsTheHeldLeaseAliveAndStopsAfterClose() throws Exception {
        JobControlGate gate = new JobControlGate(redisson, EXCL, SILENCE, "inst-1", 300, 40);

        assertTrue(gate.tryBeginControlOp());
        Thread.sleep(300);
        long renewals = calls.stream().filter(c -> c.startsWith("renew:")).count();
        assertTrue(renewals >= 2, "the daemon must renew both held leases, renewals=" + renewals);

        gate.endControlOp();
        gate.close();
    }

    /**
     * The environment cancels with release-then-close so a restarted job is not blocked for a
     * full TTL: {@code close()} alone stops the renewer and leaves the lease to expire, while
     * the explicit end is what hands the lease over immediately.
     */
    @Test
    void closeStopsTheRenewerWithoutReleasingTheHeldLease() throws Exception {
        JobControlGate gate = new JobControlGate(redisson, EXCL, SILENCE, "inst-1", 300, 40);

        assertTrue(gate.tryBeginControlOp());
        gate.close();
        Thread.sleep(120);
        long renewals = calls.stream().filter(c -> c.startsWith("renew:")).count();
        Thread.sleep(120);
        long renewalsAfterClose = calls.stream().filter(c -> c.startsWith("renew:")).count();
        assertEquals(renewals, renewalsAfterClose, "close must stop the renewal daemon");
        assertTrue(gate.isExclusiveHeld(), "close must not release the lease");
        assertEquals("inst-1:1", store.get(EXCL), "the lease stays until the TTL or an explicit end");
        assertEquals("inst-1:1", store.get(SILENCE));

        gate.endControlOp();
        assertTrue(store.isEmpty(), "the explicit end is what frees the lease");
    }

    @Test
    void contendedHoldersNeverOverlapAcrossInstances() throws Exception {
        AtomicInteger completed = new AtomicInteger(0);
        AtomicBoolean overlapped = new AtomicBoolean(false);
        JobControlGate holderA = gate("inst-A");
        JobControlGate holderB = gate("inst-B");
        JobControlGate[] holders = new JobControlGate[]{holderA, holderA, holderB, holderB};
        Thread[] threads = new Thread[holders.length];
        for (int t = 0; t < holders.length; t++) {
            final JobControlGate holder = holders[t];
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 150; i++) {
                    while (!holder.tryBeginControlOp(10_000)) {
                        Thread.yield();
                    }
                    if (!overlapped.compareAndSet(false, true)) {
                        overlapped.set(true);
                    }
                    overlapped.set(false);
                    holder.endControlOp();
                    completed.incrementAndGet();
                }
            });
        }
        for (Thread th : threads) {
            th.start();
        }
        for (Thread th : threads) {
            th.join(30_000);
            assertFalse(th.isAlive(), "a contender must finish its loop");
        }
        assertFalse(overlapped.get(), "two instances must never hold a control operation at once");
        assertEquals(holders.length * 150, completed.get());
        assertTrue(store.isEmpty(), "the storm must leave no lease behind");
    }

    /**
     * A crashed holder leaves its lease to expire by TTL; a late release from it (or a second
     * end of a stale handle) must not free the lease the new holder already took.
     */
    @Test
    void staleReleaseCannotFreeTheNewHoldersLease() {
        JobControlGate stale = gate("inst-1");
        JobControlGate fresh = gate("inst-2");

        assertTrue(stale.tryBeginControlOp());
        stale.endControlOp();
        assertTrue(fresh.tryBeginControlOp());

        stale.endControlOp();
        assertEquals("inst-2:1", store.get(EXCL),
                "a stale end must not free the exclusive lease the new holder took");
        assertEquals("inst-2:1", store.get(SILENCE),
                "a stale end must not free the silence lease the new holder took");
        assertTrue(fresh.isExclusiveHeld());

        fresh.endControlOp();
        assertTrue(store.isEmpty());
    }

    @Test
    void zeroTtlIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new JobControlGate(redisson, EXCL, SILENCE, "inst-1", 0, 0));
        assertTrue(e.getMessage().contains("ttlMillis"), e.getMessage());
    }
}
