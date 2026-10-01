package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScript;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;

/**
 * Lease-based leader election and fencing-token coordination for the Redis runtime.
 *
 * <p>All instances of a job (same {@code jobName}, different {@code jobInstanceId}) compete
 * for a Redis-backed lease key. The holder is the <em>leader</em>: only the leader runs
 * coordination duties (periodic checkpoint scheduling), which prevents split-brain
 * double-writes — without election every instance would allocate checkpoint ids from its
 * own counter and write colliding checkpoints.</p>
 *
 * <p>Lease semantics:</p>
 * <ul>
 *   <li>{@link #tryAcquireLeadership()} — atomic {@code SET key jobId NX PX ttl}; the lease
 *       expires automatically if the holder dies or stalls (e.g. long GC), so a new leader
 *       can be elected without manual intervention.</li>
 *   <li>{@link #renewLeadership()} — atomic compare-and-expire (Lua); renews only while
 *       this instance still holds the lease, so a stale holder cannot extend a lease it
 *       has already lost.</li>
 *   <li>{@link #releaseLeadership()} — atomic compare-and-delete (Lua); releases only
 *       while this instance still holds the lease.</li>
 * </ul>
 *
 * <p>Fencing token: {@link #nextFencingToken()} allocates a monotonically increasing
 * epoch token (Redis {@code INCR}) once per leadership term. Every checkpoint written
 * during the term carries the token in its meta. On restore, checkpoints whose token is
 * below the maximum token seen in the retained history are rejected — a stale leader
 * that kept writing after losing the lease (checkpoint already in flight when the lease
 * expired) cannot have its checkpoints adopted. The token is per-epoch, not per-write:
 * per-write tokens would keep advancing for a stale writer and could overtake the new
 * leader's token.</p>
 */
public final class RedisLeaderElector {

    private static final Logger log = LoggerFactory.getLogger(RedisLeaderElector.class);

    /** Renew only while this instance still holds the lease (compare-and-expire). */
    private static final String RENEW_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('PEXPIRE', KEYS[1], ARGV[2]) " +
            "else return 0 end";

    /** Release only while this instance still holds the lease (compare-and-delete). */
    private static final String RELEASE_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('DEL', KEYS[1]) " +
            "else return 0 end";

    private final RedissonClient redissonClient;
    private final RedisRuntimeConfig config;
    private final String leaderKey;
    private final String fenceKey;
    private final RBucket<String> leaderBucket;
    private final RAtomicLong fenceCounter;
    /** Epoch token of the current leadership term; 0 until {@link #nextFencingToken()} runs. */
    private volatile long currentFenceToken;

    public RedisLeaderElector(RedissonClient redissonClient, RedisRuntimeConfig config) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.config = Objects.requireNonNull(config, "config");
        this.leaderKey = config.getStateKeyPrefix() + ":" + config.getJobName() + ":leader";
        this.fenceKey = config.getStateKeyPrefix() + ":" + config.getJobName() + ":fence";
        this.leaderBucket = redissonClient.getBucket(leaderKey, StringCodec.INSTANCE);
        this.fenceCounter = redissonClient.getAtomicLong(fenceKey);
    }

    /**
     * Try to acquire the leadership lease. Returns true when this instance is the leader
     * afterwards (either the lease was free or already held by this instance).
     */
    public boolean tryAcquireLeadership() {
        try {
            Duration ttl = config.getLeaderLeaseTtl();
            Boolean acquired = leaderBucket.setIfAbsent(config.getJobInstanceId(), ttl);
            return Boolean.TRUE.equals(acquired) || isLeader();
        } catch (Exception e) {
            log.warn("Leader election acquire failed (jobName={}, instance={})",
                    config.getJobName(), config.getJobInstanceId(), e);
            return false;
        }
    }

    /**
     * Renew the leadership lease. Returns false when the lease was lost (expired or
     * taken over) — the caller must stop acting as leader immediately.
     */
    public boolean renewLeadership() {
        try {
            Long renewed = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE, RENEW_LUA, RScript.ReturnType.LONG,
                    Collections.singletonList(leaderKey),
                    config.getJobInstanceId(), String.valueOf(config.getLeaderLeaseTtl().toMillis()));
            return renewed != null && renewed > 0;
        } catch (Exception e) {
            log.warn("Leader election renew failed (jobName={}, instance={})",
                    config.getJobName(), config.getJobInstanceId(), e);
            return false;
        }
    }

    /**
     * Release the leadership lease. Returns true when this instance held the lease and
     * released it; false when it did not hold it (already lost or never acquired).
     */
    public boolean releaseLeadership() {
        try {
            Long released = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE, RELEASE_LUA, RScript.ReturnType.LONG,
                    Collections.singletonList(leaderKey),
                    config.getJobInstanceId());
            return released != null && released > 0;
        } catch (Exception e) {
            log.warn("Leader election release failed (jobName={}, instance={})",
                    config.getJobName(), config.getJobInstanceId(), e);
            return false;
        }
    }

    /**
     * The current leader's instance id, or null when no lease is held.
     */
    public String currentLeader() {
        try {
            return leaderBucket.get();
        } catch (Exception e) {
            log.debug("Failed to read leader key (jobName={})", config.getJobName(), e);
            return null;
        }
    }

    /**
     * Whether this instance currently holds the leadership lease.
     */
    public boolean isLeader() {
        try {
            return config.getJobInstanceId().equals(leaderBucket.get());
        } catch (Exception e) {
            log.debug("Failed to read leader key (jobName={})", config.getJobName(), e);
            return false;
        }
    }

    /**
     * Allocate the fencing-token epoch for a new leadership term. Must be called once
     * after a successful {@link #tryAcquireLeadership()}; the returned token is cached
     * and attached to every checkpoint written during the term.
     */
    public long nextFencingToken() {
        try {
            long token = fenceCounter.incrementAndGet();
            currentFenceToken = token;
            return token;
        } catch (Exception e) {
            log.warn("Fencing token allocation failed (jobName={}); checkpoints will carry token 0 (unfenced)",
                    config.getJobName(), e);
            return 0L;
        }
    }

    /**
     * The fencing token of the current leadership term. Falls back to the Redis counter
     * when no term token was allocated in this process yet; 0 when unknown.
     */
    public long currentFencingToken() {
        long cached = currentFenceToken;
        if (cached != 0) {
            return cached;
        }
        try {
            return Math.max(0, fenceCounter.get());
        } catch (Exception e) {
            log.debug("Failed to read fencing token (jobName={})", config.getJobName(), e);
            return 0L;
        }
    }

    public String leaderKey() {
        return leaderKey;
    }

    public String fenceKey() {
        return fenceKey;
    }
}
