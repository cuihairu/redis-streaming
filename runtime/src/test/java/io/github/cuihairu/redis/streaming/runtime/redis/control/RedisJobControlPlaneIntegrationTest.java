package io.github.cuihairu.redis.streaming.runtime.redis.control;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Redis coverage for {@link RedisJobControlPlane}: spec storage round-trips,
 * upgrade CAS (conflict detected against an external writer), rollback walking
 * history, status transitions, history caps and the audit stream content/order.
 * Skips when no Redis is reachable.
 */
@Tag("integration")
class RedisJobControlPlaneIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    private static boolean reachable(String url) {
        try (java.net.Socket s = new java.net.Socket()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("://([^/:]+):(\\d+)").matcher(url);
            if (!m.find()) {
                return false;
            }
            s.connect(new java.net.InetSocketAddress(m.group(1), Integer.parseInt(m.group(2))), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private JobSpec spec(String name, Map<String, String> config, int parallelism) {
        return JobSpec.builder()
                .jobName(name)
                .pipelineFactory("demo-factory")
                .config(config)
                .parallelism(parallelism)
                .description("it spec")
                .build();
    }

    @Test
    void submitGetListRoundTripAndDuplicateRejected() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return;
        }
        RedissonClient redis = createClient();
        String prefix = "it-cp-" + UUID.randomUUID().toString().substring(0, 8) + ":";
        try {
            RedisJobControlPlane cp = new RedisJobControlPlane(redis, prefix,
                    ControlPlaneAuthorizer.allowAll(), 100, 10);

            JobSpec stored = cp.submit(spec("jobA", Map.of("k", "v"), 2), "alice");
            assertEquals(1L, stored.getVersion());
            assertNotNull(stored.getSpecHash());

            JobSpec read = cp.get("jobA");
            assertEquals("jobA", read.getJobName());
            assertEquals("demo-factory", read.getPipelineFactory());
            assertEquals(Map.of("k", "v"), read.getConfig());
            assertEquals(2, read.getParallelism());
            assertEquals(stored.getSpecHash(), read.getSpecHash());

            assertEquals(1, cp.list().size());
            assertEquals("jobA", cp.list().get(0).getJobName());

            IllegalArgumentException dup = assertThrows(IllegalArgumentException.class,
                    () -> cp.submit(spec("jobA", Map.of(), 1), "bob"));
            assertTrue(dup.getMessage().contains("already exists"));
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void upgradeBumpsVersionHistoryGrowsAndCasDetectsExternalWriter() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return;
        }
        RedissonClient redis = createClient();
        String prefix = "it-cp-" + UUID.randomUUID().toString().substring(0, 8) + ":";
        try {
            RedisJobControlPlane cp = new RedisJobControlPlane(redis, prefix,
                    ControlPlaneAuthorizer.allowAll(), 100, 10);
            cp.submit(spec("jobA", Map.of("v", "1"), 1), "alice");

            JobSpec v2 = cp.upgrade("jobA", s -> {
                s.getConfig().put("v", "2");
                return s;
            }, "bob");
            assertEquals(2L, v2.getVersion());
            assertEquals("2", v2.getConfig().get("v"));
            assertEquals(1, redis.getList(prefix + "history:jobA",
                    org.redisson.client.codec.StringCodec.INSTANCE).size());

            JobSpec v3 = cp.upgrade("jobA", s -> {
                s.getConfig().put("v", "3");
                return s;
            }, "bob");
            assertEquals(3L, v3.getVersion());
            assertEquals(2, redis.getList(prefix + "history:jobA",
                    org.redisson.client.codec.StringCodec.INSTANCE).size());

            // external writer bumps the version out-of-band -> next upgrade must conflict, not overwrite
            RMap<String, String> versions = redis.getMap(prefix + "versions",
                    org.redisson.client.codec.StringCodec.INSTANCE);
            versions.put("jobA", "99");
            IllegalStateException conflict = assertThrows(IllegalStateException.class,
                    () -> cp.upgrade("jobA", s -> s, "carol"));
            assertTrue(conflict.getMessage().contains("Concurrent modification"));
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void rollbackWalksBackThroughHistory() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return;
        }
        RedissonClient redis = createClient();
        String prefix = "it-cp-" + UUID.randomUUID().toString().substring(0, 8) + ":";
        try {
            RedisJobControlPlane cp = new RedisJobControlPlane(redis, prefix,
                    ControlPlaneAuthorizer.allowAll(), 100, 10);
            cp.submit(spec("jobA", Map.of("v", "1"), 1), "alice");
            cp.upgrade("jobA", s -> {
                s.getConfig().put("v", "2");
                return s;
            }, "bob");
            cp.upgrade("jobA", s -> {
                s.getConfig().put("v", "3");
                return s;
            }, "bob");

            // first rollback -> previous version's content, new version number
            JobSpec r1 = cp.rollback("jobA", "carol");
            assertEquals(4L, r1.getVersion());
            assertEquals("2", r1.getConfig().get("v"));

            // consecutive rollback walks one more step back through history
            JobSpec r2 = cp.rollback("jobA", "carol");
            assertEquals(5L, r2.getVersion());
            assertEquals("3", r2.getConfig().get("v"));

            // spec store reflects the rollback result
            assertEquals(r2.getSpecHash(), cp.get("jobA").getSpecHash());

            assertThrows(IllegalArgumentException.class,
                    () -> cp.rollback("ghost", "carol"));
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void stopResumeStatusAndReportedState() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return;
        }
        RedissonClient redis = createClient();
        String prefix = "it-cp-" + UUID.randomUUID().toString().substring(0, 8) + ":";
        try {
            RedisJobControlPlane cp = new RedisJobControlPlane(redis, prefix,
                    ControlPlaneAuthorizer.allowAll(), 100, 10);
            cp.submit(spec("jobA", Map.of(), 1), "alice");

            assertEquals(JobState.PENDING_DEPLOY, cp.status("jobA").getState());

            cp.reportStatus("jobA", JobState.RUNNING, "inst-1", null);
            JobStatus running = cp.status("jobA");
            assertEquals(JobState.RUNNING, running.getState());
            assertEquals("inst-1", running.getInstanceId());

            cp.stop("jobA", "op");
            assertEquals(JobState.DESIRED_STOPPED, cp.status("jobA").getState());

            cp.resume("jobA", "op");
            JobStatus resumed = cp.status("jobA");
            assertEquals(JobState.PENDING_DEPLOY, resumed.getState());
            assertEquals("", resumed.getInstanceId());

            cp.reportStatus("jobA", JobState.FAILED, "inst-2", "boom");
            assertEquals(JobState.FAILED, cp.status("jobA").getState());
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void auditRecordsAllowedDeniedAndFailureEntriesNewestFirst() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return;
        }
        RedissonClient redis = createClient();
        String prefix = "it-cp-" + UUID.randomUUID().toString().substring(0, 8) + ":";
        try {
            ControlPlaneAuthorizer denyUpgrade = (actor, op, job) -> {
                if (op == JobControlOp.UPGRADE) {
                    throw new ControlPlaneAccessDeniedException("upgrade locked down");
                }
            };
            RedisJobControlPlane cp = new RedisJobControlPlane(redis, prefix, denyUpgrade, 100, 10);
            cp.submit(spec("jobA", Map.of(), 1), "alice");
            assertThrows(ControlPlaneAccessDeniedException.class,
                    () -> cp.upgrade("jobA", s -> s, "eve"));
            cp.stop("jobA", "op");
            cp.reportStatus("jobA", JobState.FAILED, "inst-1", "boom");

            List<AuditEntry> tail = cp.tailAudit(10);
            assertFalse(tail.isEmpty());
            // newest first
            assertEquals(JobControlOp.REPORT_STATUS, tail.get(0).getOp());
            assertTrue(tail.get(0).getDetail().contains("boom"));

            AuditEntry denied = tail.stream()
                    .filter(a -> a.getOp() == JobControlOp.UPGRADE)
                    .findFirst().orElseThrow();
            assertFalse(denied.isAllowed());
            assertEquals("eve", denied.getActor());

            AuditEntry stopped = tail.stream()
                    .filter(a -> a.getOp() == JobControlOp.STOP)
                    .findFirst().orElseThrow();
            assertTrue(stopped.isAllowed());
            assertEquals("op", stopped.getActor());
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void historyCapTrimsOldestEntries() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return;
        }
        RedissonClient redis = createClient();
        String prefix = "it-cp-" + UUID.randomUUID().toString().substring(0, 8) + ":";
        try {
            RedisJobControlPlane cp = new RedisJobControlPlane(redis, prefix,
                    ControlPlaneAuthorizer.allowAll(), 100, 2);
            cp.submit(spec("jobA", Map.of("v", "0"), 1), "alice");
            for (int i = 1; i <= 4; i++) {
                int v = i;
                cp.upgrade("jobA", s -> {
                    s.getConfig().put("v", String.valueOf(v));
                    return s;
                }, "bob");
            }
            assertEquals(5L, cp.get("jobA").getVersion());
            List<String> history = redis.getList(prefix + "history:jobA",
                    org.redisson.client.codec.StringCodec.INSTANCE);
            assertEquals(2, history.size());
            // cap kept the two newest history entries: [v3-spec(v=2), v4-spec(v=3)] —
            // the last entry is the pre-image of the final upgrade (v4 spec, config v=3)
            assertTrue(history.get(1).contains("\"v\":\"3\""));
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }
}
