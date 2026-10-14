package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The cross-instance job control gate: two TTL lease keys per job ({@code ctl:excl} and
 * {@code ctl:silence}) that serialize the runtime's exclusive control operations — aligned
 * checkpoint flows and {@code scaleParallelism} — across every instance of the job
 * (docs/Job-Global-Control-Gate-Design).
 *
 * <p>Why it exists: {@link CheckpointGate} only serializes within one process. Two clients of
 * the same job (HA takeover overlap, an ops tool starting a second instance, a control-plane
 * double agent) could each run a checkpoint concurrently — pause their own consumers, drain and
 * snapshot into the same checkpoint space, and land torn snapshots and out-of-order stores that
 * restore's max-fencing-token selection cannot resolve (both writes carry the same epoch token).
 * Leader election alone cannot fix this: it coordinates <em>periodic triggers</em>, not manual
 * triggers and not scale.
 *
 * <p>The lattice mirrors {@link CheckpointGate}: exclusive first, full silence second,
 * release in reverse, so {@code silence ⇒ exclusive} holds across instances too and no
 * acquisition order can deadlock. Every lease is {@code SET NX PX} plus a Lua compare-and-release:
 * a holder that crashes self-heals by TTL (no release call needed), a stale holder's late
 * release cannot free the lease of the instance that took over after it, and an unreachable
 * Redis means refusal (fail-safe) rather than a blind write.
 *
 * <p>While held, a daemon renews both leases at a third of the TTL; a Redis blip during
 * renewal degrades to TTL expiry, the same bound the election lease already accepts.
 */
public final class JobControlGate {

    private static final Logger log = LoggerFactory.getLogger(JobControlGate.class);

    /** Poll spacing for a positive wait budget. */
    private static final long POLL_INTERVAL_MILLIS = 50L;

    /**
     * Grant or renew the lease: reentrant for the current holder (refresh), NX-grant for a free
     * key, refused while another instance holds it.
     */
    private static final String ACQUIRE_LUA =
            "local v = redis.call('GET', KEYS[1]) " +
            "if v == false then " +
            "  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2]) " +
            "  return 1 " +
            "elseif v == ARGV[1] then " +
            "  redis.call('PEXPIRE', KEYS[1], ARGV[2]) " +
            "  return 1 " +
            "else return 0 end";

    /** Extend the TTL only while this instance still holds the lease. */
    private static final String RENEW_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('PEXPIRE', KEYS[1], ARGV[2]) " +
            "else return 0 end";

    /** Free the lease only while this instance still holds it (compare-and-delete). */
    private static final String RELEASE_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('DEL', KEYS[1]) " +
            "else return 0 end";

    private final RedissonClient redissonClient;
    private final String exclusiveKey;
    private final String silenceKey;
    private final String holderInstanceId;
    private final long ttlMillis;
    private final long renewPeriodMillis;
    private final AtomicBoolean held = new AtomicBoolean(false);
    private final AtomicLong opSeq = new AtomicLong(0);
    /**
     * The {@code holderInstanceId:opSeq} value the currently held lease was granted under, or
     * null when no lease is open. Each begin bumps the sequence, so a begin that follows a
     * release which failed to reach Redis cannot re-enter the old value and share a lease with
     * a stale op — it has to wait out the TTL.
     */
    private volatile String heldBy;
    private ScheduledExecutorService renewer;

    public JobControlGate(RedissonClient redissonClient, String exclusiveKey, String silenceKey,
                          String holderInstanceId, long ttlMillis, long renewPeriodMillis) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.exclusiveKey = Objects.requireNonNull(exclusiveKey, "exclusiveKey");
        this.silenceKey = Objects.requireNonNull(silenceKey, "silenceKey");
        this.holderInstanceId = Objects.requireNonNull(holderInstanceId, "holderInstanceId");
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be > 0");
        }
        this.ttlMillis = ttlMillis;
        this.renewPeriodMillis = renewPeriodMillis;
    }

    /**
     * Try to win the job-global control lease (both levels) exactly once.
     *
     * @return true when this instance now holds the lease and the caller must end it with
     *         {@link #endControlOp()}
     */
    public boolean tryBeginControlOp() {
        return tryBeginControlOp(0L);
    }

    /**
     * Try to win the job-global control lease, polling while another instance holds it.
     *
     * @param waitMillis budget for the wait; zero means fail-fast
     * @return true when this instance now holds the lease and the caller must end it with
     *         {@link #endControlOp()}
     */
    public boolean tryBeginControlOp(long waitMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, waitMillis);
        do {
            if (tryAcquireBothLevels()) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            long remaining = Math.max(1L, deadline - System.currentTimeMillis());
            try {
                Thread.sleep(Math.min(POLL_INTERVAL_MILLIS, remaining));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (true);
    }

    /**
     * Release the control lease — silence first, exclusive second (reverse of the acquisition
     * order), compare-and-delete. Best-effort: a release after the lease expired, or a repeat
     * end, frees nothing, which is exactly why a stale holder cannot release the lease of the
     * instance that took over after it.
     */
    public void endControlOp() {
        if (held.compareAndSet(true, false)) {
            releaseKey(silenceKey);
            releaseKey(exclusiveKey);
        }
    }

    /**
     * Renew both held leases (compare-and-expire). No-op while this instance holds nothing; a
     * refused renewal means the TTL already expired and someone else took over — logged so the
     * operator can see the handover instead of discovering it as a torn snapshot.
     */
    public void renewHeldNow() {
        try {
            if (!held.get()) {
                return;
            }
            String holder = heldBy;
            boolean exclusiveRenewed = eval(RENEW_LUA, exclusiveKey, holder, ttlMillis);
            boolean silenceRenewed = eval(RENEW_LUA, silenceKey, holder, ttlMillis);
            if (!exclusiveRenewed || !silenceRenewed) {
                log.warn("Control gate lease lost during renewal (jobKey={}, exclusiveRenewed={}, silenceRenewed={})",
                        exclusiveKey, exclusiveRenewed, silenceRenewed);
            }
        } catch (Exception e) {
            log.warn("Control gate renew failed (jobKey={})", exclusiveKey, e);
        }
    }

    /** Whether this instance currently holds the exclusive level. */
    public boolean isExclusiveHeld() {
        return held.get();
    }

    /** Whether this instance currently holds the full-silence level (a superset view). */
    public boolean isFullSilenceHeld() {
        return held.get();
    }

    /**
     * Stop the renewal daemon. Leases still held expire by TTL; no release is attempted — a
     * caller that owns the held lease and wants an immediate handover (a graceful shutdown)
     * must call {@link #endControlOp()} first, which is what the environment does on cancel.
     */
    public void close() {
        synchronized (this) {
            if (renewer != null) {
                renewer.shutdownNow();
                renewer = null;
            }
        }
    }

    private boolean tryAcquireBothLevels() {
        // a fresh sequence per begin: see heldBy
        String holder = holderInstanceId + ":" + opSeq.incrementAndGet();
        try {
            if (!eval(ACQUIRE_LUA, exclusiveKey, holder, ttlMillis)) {
                return false;
            }
            boolean silenceWon;
            try {
                silenceWon = eval(ACQUIRE_LUA, silenceKey, holder, ttlMillis);
            } catch (Exception e) {
                log.warn("Control gate acquire failed (jobKey={})", exclusiveKey, e);
                releaseKey(exclusiveKey, holder);
                return false;
            }
            if (!silenceWon) {
                // defensive: another instance's crash-leftover silence lease can break
                // silence ⇒ exclusive; stranding the won exclusive level here would wedge every
                // future control operation until the TTL expired
                releaseKey(exclusiveKey, holder);
                return false;
            }
        } catch (Exception e) {
            // Redis unreachable: refuse rather than run blind. A checkpoint that cannot prove it
            // holds the lease must not pause consumers and write into the shared checkpoint space.
            log.warn("Control gate acquire failed (jobKey={})", exclusiveKey, e);
            return false;
        }
        heldBy = holder;
        held.set(true);
        startRenewer();
        return true;
    }

    private void releaseKey(String key) {
        releaseKey(key, heldBy);
    }

    private void releaseKey(String key, String holder) {
        try {
            eval(RELEASE_LUA, key, holder);
        } catch (Exception e) {
            log.warn("Control gate release failed for key {}", key, e);
        }
    }

    /**
     * Run a lease script that takes a TTL ({@code ARGV[2]}). The release path does not use
     * this overload: passing a TTL the script never reads would leave {@code ARGV[2]} set
     * for nothing, and dropping the TTL from a script that does read it makes Redis reject
     * the call ("Lua redis lib command arguments must be strings or integers") — the silent
     * failure mode that shipped as "always refused" until an integration test caught it.
     */
    private boolean eval(String lua, String key, String holder, long ttl) {
        Long granted = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, lua, RScript.ReturnType.LONG,
                Collections.singletonList(key), holder, String.valueOf(ttl));
        return granted != null && granted > 0;
    }

    /** Run a lease script that reads only the holder value ({@code ARGV[1]}) — the release. */
    private boolean eval(String lua, String key, String holder) {
        Long granted = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, lua, RScript.ReturnType.LONG,
                Collections.singletonList(key), holder);
        return granted != null && granted > 0;
    }

    private void startRenewer() {
        if (renewPeriodMillis <= 0) {
            return;
        }
        synchronized (this) {
            if (renewer != null) {
                return;
            }
            ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "job-control-gate-renewer");
                    t.setDaemon(true);
                    return t;
                }
            });
            ex.scheduleAtFixedRate(this::renewHeldNow, renewPeriodMillis, renewPeriodMillis,
                    TimeUnit.MILLISECONDS);
            renewer = ex;
        }
    }
}
