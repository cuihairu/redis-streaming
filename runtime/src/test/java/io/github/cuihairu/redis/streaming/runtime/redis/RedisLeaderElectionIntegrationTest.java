package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leader election and fencing-token coordination on real Redis: exactly one leader per
 * job, leader-only periodic checkpointing, checkpoints carrying the leadership epoch
 * token, and automatic takeover when the leader stops.
 */
@Tag("integration")
class RedisLeaderElectionIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    private static RedisRuntimeConfig config(String prefix, String instanceId) {
        return RedisRuntimeConfig.builder()
                .jobName("elect-job")
                .jobInstanceId(instanceId)
                .stateKeyPrefix(prefix)
                .checkpointKeyPrefix(prefix + ":cp:")
                .leaderElectionEnabled(true)
                .leaderLeaseTtl(Duration.ofSeconds(3))
                .leaderRenewInterval(Duration.ofMillis(500))
                .checkpointInterval(Duration.ofMillis(200))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).consumerPollTimeoutMs(100).build())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static boolean isLeader(RedisJobClient job) {
        Map<String, Object> diag = job.diagnostics();
        return Boolean.TRUE.equals(diag.get("isLeader"));
    }

    /**
     * Counts stored checkpoints by the jobInstanceId recorded in their meta. Both instances
     * share one checkpoint key space, so attribution requires reading the meta — this is
     * what makes "only the leader checkpoints periodically" observable.
     */
    private static Map<String, Integer> countCheckpointsByInstance(RedissonClient redis, String cpPrefix) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (String key : redis.getKeys().getKeysByPattern(cpPrefix + "*")) {
            try {
                Checkpoint cp = (Checkpoint) redis.getBucket(key).get();
                if (cp == null) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) cp.getStateSnapshot().getState("runtime:meta");
                String inst = meta == null ? "?" : String.valueOf(meta.get("jobInstanceId"));
                counts.merge(inst, 1, Integer::sum);
            } catch (Exception ignore) {
                // skip unreadable checkpoint keys
            }
        }
        return counts;
    }

    @Test
    void exactlyOneLeaderAndOnlyLeaderCheckpointsPeriodically() throws Exception {
        String prefix = "streaming:elect:" + UUID.randomUUID().toString().substring(0, 8);
        String topic = "elect-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "g";
        RedissonClient redis = client();
        try {
            RedisStreamExecutionEnvironment envA = RedisStreamExecutionEnvironment.create(redis, config(prefix, "inst-A"));
            envA.fromMqTopic(topic, group).addSink(v -> {
            });
            RedisJobClient jobA = envA.executeAsync();

            RedisStreamExecutionEnvironment envB = RedisStreamExecutionEnvironment.create(redis, config(prefix, "inst-B"));
            envB.fromMqTopic(topic, group).addSink(v -> {
            });
            RedisJobClient jobB = envB.executeAsync();

            // exactly one leader
            assertTrue(isLeader(jobA) ^ isLeader(jobB), "exactly one instance must hold the leadership lease");

            // periodic checkpointing runs only on the leader
            Thread.sleep(2_000);
            // re-determine: leadership must not have flipped, but read it fresh regardless
            assertTrue(isLeader(jobA) ^ isLeader(jobB), "leadership must remain stable during the observation window");
            String leaderInstance = isLeader(jobA) ? "inst-A" : "inst-B";
            String followerInstance = isLeader(jobA) ? "inst-B" : "inst-A";

            Map<String, Integer> counts = countCheckpointsByInstance(redis, prefix + ":cp:" + "elect-job" + ":");
            int leaderCount = counts.getOrDefault(leaderInstance, 0);
            int followerCount = counts.getOrDefault(followerInstance, 0);
            assertTrue(leaderCount >= 5,
                    "leader must run periodic checkpoints (200ms interval over 2s), counts=" + counts);
            assertEquals(0, followerCount, "follower must not run periodic checkpoints, counts=" + counts);

            // checkpoints carry the leadership epoch fencing token
            Checkpoint leaderCp = isLeader(jobA) ? jobA.getLatestCheckpoint() : jobB.getLatestCheckpoint();
            assertNotNull(leaderCp, "leader must have a latest checkpoint");
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) leaderCp.getStateSnapshot().getState("runtime:meta");
            assertNotNull(meta, "checkpoint meta must exist");
            assertTrue(meta.get("fencingToken") instanceof Number n && n.longValue() > 0,
                    "leader checkpoint must carry a positive fencing token, meta=" + meta);

            jobA.cancel();
            jobB.cancel();
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void leadershipTransfersToFollowerWhenLeaderStops() throws Exception {
        String prefix = "streaming:elect2:" + UUID.randomUUID().toString().substring(0, 8);
        String topic = "elect2-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "g";
        RedissonClient redis = client();
        try {
            RedisStreamExecutionEnvironment envA = RedisStreamExecutionEnvironment.create(redis, config(prefix, "inst-A"));
            envA.fromMqTopic(topic, group).addSink(v -> {
            });
            RedisJobClient jobA = envA.executeAsync();

            RedisStreamExecutionEnvironment envB = RedisStreamExecutionEnvironment.create(redis, config(prefix, "inst-B"));
            envB.fromMqTopic(topic, group).addSink(v -> {
            });
            RedisJobClient jobB = envB.executeAsync();

            assertTrue(isLeader(jobA) ^ isLeader(jobB), "exactly one instance must hold the leadership lease");
            RedisJobClient leader = isLeader(jobA) ? jobA : jobB;
            RedisJobClient follower = isLeader(jobA) ? jobB : jobA;

            leader.cancel();

            // the follower's renew task notices the free lease and takes over
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && !isLeader(follower)) {
                Thread.sleep(100);
            }
            assertTrue(isLeader(follower), "follower must take over after the leader stops");

            // the new leader checkpoints with a fresh epoch token
            Thread.sleep(1_000);
            Checkpoint cp = follower.getLatestCheckpoint();
            assertNotNull(cp, "new leader must run periodic checkpoints");
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) cp.getStateSnapshot().getState("runtime:meta");
            assertTrue(meta != null && meta.get("fencingToken") instanceof Number n && n.longValue() > 0,
                    "new leader checkpoint must carry a positive fencing token");

            follower.cancel();
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void manualCheckpointWorksOnFollowerAndCarriesSharedEpochToken() throws Exception {
        String prefix = "streaming:elect3:" + UUID.randomUUID().toString().substring(0, 8);
        String topic = "elect3-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "g";
        RedissonClient redis = client();
        try {
            RedisStreamExecutionEnvironment envA = RedisStreamExecutionEnvironment.create(redis, config(prefix, "inst-A"));
            envA.fromMqTopic(topic, group).addSink(v -> {
            });
            RedisJobClient jobA = envA.executeAsync();

            RedisStreamExecutionEnvironment envB = RedisStreamExecutionEnvironment.create(redis, config(prefix, "inst-B"));
            envB.fromMqTopic(topic, group).addSink(v -> {
            });
            RedisJobClient jobB = envB.executeAsync();

            RedisJobClient follower = isLeader(jobA) ? jobB : jobA;

            // manual triggers are not leader-gated: a follower may write a valid checkpoint.
            // a null return means another checkpoint was in flight (transient) — retry.
            Checkpoint cp = null;
            long deadline = System.currentTimeMillis() + 15_000;
            while (cp == null && System.currentTimeMillis() < deadline) {
                cp = follower.triggerCheckpointNow();
                if (cp == null) {
                    Thread.sleep(200);
                }
            }
            assertNotNull(cp, "follower manual checkpoint must succeed");
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) cp.getStateSnapshot().getState("runtime:meta");
            assertTrue(meta != null && meta.get("fencingToken") instanceof Number n && n.longValue() > 0,
                    "follower checkpoint must carry the current epoch fencing token");

            jobA.cancel();
            jobB.cancel();
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }
}
