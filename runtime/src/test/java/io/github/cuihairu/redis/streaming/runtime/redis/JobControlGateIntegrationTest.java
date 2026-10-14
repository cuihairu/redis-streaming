package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.JobControlGate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The job-global control gate on real Redis (docs/Job-Global-Control-Gate-Design §6): two
 * clients of one job serialize their control operations across instances, a crashed holder
 * self-heals by TTL, an unreachable Redis refuses control operations instead of letting them
 * tear, the lattice holds under a contention storm, and a disabled gate stays completely
 * inert.
 */
@Tag("integration")
class JobControlGateIntegrationTest {

    private static final long TTL_MS = 60_000;

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    private static RedisRuntimeConfig config(String prefix, String instanceId) {
        return config(prefix, instanceId, null);
    }

    private static RedisRuntimeConfig config(String prefix, String instanceId, Boolean gateOverride) {
        RedisRuntimeConfig.Builder b = RedisRuntimeConfig.builder()
                .jobName("ctl-job")
                .jobInstanceId(instanceId)
                .stateKeyPrefix(prefix)
                .checkpointKeyPrefix(prefix + ":cp:")
                // leader election on and no override: the gate rides along — this is the
                // production combination the feature is designed for
                .leaderElectionEnabled(true)
                .leaderLeaseTtl(Duration.ofSeconds(3))
                .leaderRenewInterval(Duration.ofMillis(500))
                .checkpointInterval(Duration.ofMillis(200))
                .checkpointsToKeep(0)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).consumerPollTimeoutMs(100).build());
        if (gateOverride != null) {
            b.jobControlGateEnabled(gateOverride);
        }
        return b.build();
    }

    private static JobControlGate rawGate(RedissonClient redis, String prefix, String holder,
                                          long ttlMillis, long renewMillis) {
        return new JobControlGate(redis,
                prefix + ":ctl-job:ctl:excl", prefix + ":ctl-job:ctl:silence",
                holder, ttlMillis, renewMillis);
    }

    @SuppressWarnings("unchecked")
    private static boolean isLeader(RedisJobClient job) {
        return Boolean.TRUE.equals(job.diagnostics().get("isLeader"));
    }

    private static void cleanup(String prefix) {
        RedissonClient redis = client();
        try {
            redis.getKeys().deleteByPattern(prefix + "*");
        } finally {
            redis.shutdown();
        }
    }

    /** Redisson 4.7's RKeys scan yields an Iterable, not a Collection. */
    private static boolean anyKeyMatches(RedissonClient redis, String pattern) {
        for (String key : redis.getKeys().getKeysByPattern(pattern)) {
            return true;
        }
        return false;
    }

    @Test
    void seededLeaseRefusesCheckpointAndScaleUntilReleased() throws Exception {
        String prefix = "streaming:ctls:" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        RedisJobClient job = null;
        try {
            JobControlGate seeder = rawGate(redis, prefix, "seeder", TTL_MS, TTL_MS / 3);
            assertTrue(seeder.tryBeginControlOp(), "the seeder must hold the job-global lease");

            RedisStreamExecutionEnvironment env =
                    RedisStreamExecutionEnvironment.create(redis, config(prefix, "inst-A"));
            env.fromMqTopic("ctls-" + prefix, "g").addSink(v -> {
            });
            job = env.executeAsync();

            assertNull(job.triggerCheckpointNow(),
                    "a checkpoint while another instance holds the lease must be refused");
            assertFalse(job.scaleParallelism(2),
                    "a scale while another instance holds the lease must be refused");
            assertFalse(anyKeyMatches(redis, prefix + ":cp:*"),
                    "the refused checkpoint must not have stored anything");

            seeder.endControlOp();

            Checkpoint after = null;
            long deadline = System.currentTimeMillis() + 15_000;
            while (after == null && System.currentTimeMillis() < deadline) {
                after = job.triggerCheckpointNow();
                if (after == null) {
                    Thread.sleep(100);
                }
            }
            assertNotNull(after, "after the lease is released the checkpoint must succeed");
        } finally {
            if (job != null) {
                job.cancel();
            }
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void twoEnvironmentsSerializeControlOperations() throws Exception {
        String prefix = "streaming:ctlt:" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        try {
            RedisStreamExecutionEnvironment envA =
                    RedisStreamExecutionEnvironment.create(redis, config(prefix, "inst-A"));
            envA.fromMqTopic("ctlt-a-" + prefix, "g").addSink(v -> {
            });
            RedisJobClient jobA = envA.executeAsync();

            RedisStreamExecutionEnvironment envB =
                    RedisStreamExecutionEnvironment.create(redis, config(prefix, "inst-B"));
            envB.fromMqTopic("ctlt-b-" + prefix, "g").addSink(v -> {
            });
            RedisJobClient jobB = envB.executeAsync();

            try {
                assertTrue(isLeader(jobA) ^ isLeader(jobB), "exactly one leader must be elected");

                // the follower's manual checkpoint must win the lease in the gaps the leader's
                // periodic checkpoints leave open (retry until the lease is free for a moment)
                Checkpoint cp = null;
                long deadline = System.currentTimeMillis() + 20_000;
                RedisJobClient follower = isLeader(jobA) ? jobB : jobA;
                while (cp == null && System.currentTimeMillis() < deadline) {
                    cp = follower.triggerCheckpointNow();
                    if (cp == null) {
                        Thread.sleep(100);
                    }
                }
                assertNotNull(cp,
                        "a follower manual checkpoint must succeed once the lease frees up");
            } finally {
                jobA.cancel();
                jobB.cancel();
            }
        } finally {
            cleanup(prefix);
            redis.shutdown();
        }
    }

    @Test
    void crossInstanceLatticeHoldsUnderContention() throws Exception {
        String prefix = "streaming:ctll:" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        try {
            JobControlGate gateA = rawGate(redis, prefix, "lat-A", TTL_MS, 0);
            JobControlGate gateB = rawGate(redis, prefix, "lat-B", TTL_MS, 0);
            JobControlGate[] gates = new JobControlGate[]{gateA, gateA, gateB, gateB};

            AtomicBoolean inCritical = new AtomicBoolean(false);
            AtomicBoolean overlapped = new AtomicBoolean(false);
            AtomicInteger completed = new AtomicInteger(0);
            Thread[] threads = new Thread[gates.length];
            for (int t = 0; t < gates.length; t++) {
                final JobControlGate gate = gates[t];
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < 100; i++) {
                        while (!gate.tryBeginControlOp(10_000)) {
                            Thread.yield();
                        }
                        if (!inCritical.compareAndSet(false, true)) {
                            overlapped.set(true);
                        }
                        inCritical.set(false);
                        gate.endControlOp();
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
            assertFalse(overlapped.get(), "two instances must never hold the control operation at once");
            assertEquals(gates.length * 100, completed.get());
            assertFalse(anyKeyMatches(redis, prefix + ":ctl-job:ctl:*"),
                    "the storm must leave no lease behind");
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void ttlSelfHealLetsTheNextHolderTakeOverAndStaleReleaseCannotFreeIt() throws Exception {
        String prefix = "streaming:ctlh:" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        try {
            long ttl = 600L;
            JobControlGate victim = rawGate(redis, prefix, "victim", ttl, 0);
            JobControlGate healer = rawGate(redis, prefix, "healer", ttl, 0);

            assertTrue(victim.tryBeginControlOp(), "the victim takes the lease");
            assertFalse(healer.tryBeginControlOp(), "the healer must wait out the victim");

            Thread.sleep(ttl + 300);
            assertTrue(healer.tryBeginControlOp(),
                    "after the TTL the lease must self-heal to the next holder");
            String healerHolder = String.valueOf(
                    redis.getBucket(prefix + ":ctl-job:ctl:excl", StringCodec.INSTANCE).get());
            assertTrue(healerHolder.startsWith("healer:"),
                    "the healer's holder value must have replaced the victim's: " + healerHolder);

            victim.endControlOp();
            assertEquals(healerHolder,
                    redis.getBucket(prefix + ":ctl-job:ctl:excl", StringCodec.INSTANCE).get(),
                    "the victim's stale release must not free the healer's lease");

            healer.endControlOp();
            assertFalse(anyKeyMatches(redis, prefix + ":ctl-job:ctl:*"));
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void redisUnreachableRefusesTheControlOperation() {
        String prefix = "streaming:ctld:" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        RedisJobClient job = null;
        try {
            RedisStreamExecutionEnvironment env =
                    RedisStreamExecutionEnvironment.create(redis, config(prefix, "inst-A"));
            env.fromMqTopic("ctld-" + prefix, "g").addSink(v -> {
            });
            job = env.executeAsync();

            redis.shutdown();

            assertNull(job.triggerCheckpointNow(),
                    "an unreachable Redis must refuse a checkpoint (fail-safe), never tear one");
            assertFalse(job.scaleParallelism(2),
                    "an unreachable Redis must refuse a scale, never race the subtask lists");
        } finally {
            if (job != null) {
                try {
                    job.cancel();
                } catch (Exception ignore) {
                    // the client is dead on purpose; teardown must not mask the assertions
                }
            }
            cleanup(prefix);
        }
    }

    @Test
    void disabledGateIsCompletelyInert() {
        String prefix = "streaming:ctli:" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        RedisJobClient job = null;
        try {
            // leader election stays on; only the gate is switched off
            RedisRuntimeConfig cfg = config(prefix, "inst-A", false);
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            env.fromMqTopic("ctli-" + prefix, "g").addSink(v -> {
            });
            job = env.executeAsync();

            assertNotNull(job.triggerCheckpointNow(),
                    "a disabled gate must not disturb the checkpoint flow");
            assertFalse(anyKeyMatches(redis, prefix + ":ctl-job:ctl:*"),
                    "no lease key may be created while the gate is disabled");
            assertTrue(job.scaleParallelism(2), "a disabled gate must not disturb the scale flow");
            assertTrue(job.scaleParallelism(1), "a second scale must still succeed");
        } finally {
            if (job != null) {
                job.cancel();
            }
            cleanup(prefix);
            redis.shutdown();
        }
    }

    @Test
    void followerManualCheckpointCarriesTheEpochTokenUnderTheGate() throws Exception {
        String prefix = "streaming:ctlf:" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        try {
            RedisStreamExecutionEnvironment envA =
                    RedisStreamExecutionEnvironment.create(redis, config(prefix, "inst-A"));
            envA.fromMqTopic("ctlf-a-" + prefix, "g").addSink(v -> {
            });
            RedisJobClient jobA = envA.executeAsync();

            RedisStreamExecutionEnvironment envB =
                    RedisStreamExecutionEnvironment.create(redis, config(prefix, "inst-B"));
            envB.fromMqTopic("ctlf-b-" + prefix, "g").addSink(v -> {
            });
            RedisJobClient jobB = envB.executeAsync();

            try {
                assertTrue(isLeader(jobA) ^ isLeader(jobB), "exactly one leader must be elected");
                RedisJobClient follower = isLeader(jobA) ? jobB : jobA;

                // manual checkpoints remain a designed follower path even with the gate on
                Checkpoint cp = null;
                long deadline = System.currentTimeMillis() + 20_000;
                while (cp == null && System.currentTimeMillis() < deadline) {
                    cp = follower.triggerCheckpointNow();
                    if (cp == null) {
                        Thread.sleep(100);
                    }
                }
                assertNotNull(cp, "a follower manual checkpoint must still land");
                @SuppressWarnings("unchecked")
                Map<String, Object> meta =
                        (Map<String, Object>) cp.getStateSnapshot().getState("runtime:meta");
                assertNotNull(meta, "the follower checkpoint must carry meta");
                assertTrue(meta.get("fencingToken") instanceof Number n && n.longValue() > 0,
                        "the follower checkpoint must carry the shared epoch token");
            } finally {
                jobA.cancel();
                jobB.cancel();
            }
        } finally {
            cleanup(prefix);
            redis.shutdown();
        }
    }
}
