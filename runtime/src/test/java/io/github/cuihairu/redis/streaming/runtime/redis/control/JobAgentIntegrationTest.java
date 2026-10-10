package io.github.cuihairu.redis.streaming.runtime.redis.control;

import io.github.cuihairu.redis.streaming.runtime.redis.RedisJobClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Redis coverage for {@link JobAgent} + {@link RedisJobControlPlane}: claim-
 * serialized deploy, upgrade on spec drift, parallelism fast path, desired-stop
 * cancel and two-agent claim arbitration. Uses a fake launcher (no real pipelines),
 * so all control-plane mechanics run against real Redis. Skips when unreachable.
 */
@Tag("integration")
class JobAgentIntegrationTest {

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

    /** Fake job handle counting cancels; scale fast path reports success. */
    static class FakeJob implements RedisJobClient {
        final AtomicInteger cancels = new AtomicInteger();
        final AtomicInteger scales = new AtomicInteger();

        @Override
        public void cancel() {
            cancels.incrementAndGet();
        }

        @Override
        public boolean awaitTermination(Duration timeout) {
            return true;
        }

        @Override
        public boolean scaleParallelism(int newParallelism) {
            scales.incrementAndGet();
            return true;
        }
    }

    /** Fake launcher: records launched specs, hands out per-launch fake jobs. */
    static class RecordingLauncher implements JobLauncher {
        final List<JobSpec> launched = new CopyOnWriteArrayList<>();
        final List<FakeJob> jobs = new CopyOnWriteArrayList<>();
        private boolean failNext = false;

        void failNextLaunch() {
            this.failNext = true;
        }

        @Override
        public RedisJobClient launch(JobSpec spec) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("injected launch failure");
            }
            launched.add(spec);
            FakeJob job = new FakeJob();
            jobs.add(job);
            return job;
        }
    }

    private JobSpec spec(String name, Map<String, String> config, int parallelism) {
        return JobSpec.builder()
                .jobName(name)
                .pipelineFactory("f")
                .config(config)
                .parallelism(parallelism)
                .build();
    }

    @Test
    void deployUpgradeScaleStopLifecycleAgainstRealControlPlane() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return;
        }
        RedissonClient redis = createClient();
        String prefix = "it-agent-" + UUID.randomUUID().toString().substring(0, 8) + ":";
        try {
            RedisJobControlPlane plane = new RedisJobControlPlane(redis, prefix,
                    ControlPlaneAuthorizer.allowAll(), 100, 10);
            RecordingLauncher launcher = new RecordingLauncher();
            JobAgent agent = new JobAgent(plane, launcher, redis, "inst-A",
                    Duration.ofSeconds(1), prefix + "claim:");

            plane.submit(spec("jobA", Map.of("v", "1"), 1), "op");
            agent.reconcileOnce();
            assertEquals(1, launcher.launched.size());
            assertEquals(JobState.RUNNING, plane.status("jobA").getState());
            assertEquals("inst-A", plane.status("jobA").getInstanceId());
            assertEquals(0, launcher.jobs.get(0).cancels.get());

            // full upgrade: config change -> cancel old + relaunch
            plane.upgrade("jobA", s -> {
                s.getConfig().put("v", "2");
                return s;
            }, "op");
            agent.reconcileOnce();
            assertEquals(2, launcher.launched.size());
            assertEquals(1, launcher.jobs.get(0).cancels.get());
            assertEquals("2", launcher.launched.get(1).getConfig().get("v"));

            // parallelism-only change -> scale fast path, no relaunch, no cancel
            plane.upgrade("jobA", s -> {
                s.setParallelism(3);
                return s;
            }, "op");
            agent.reconcileOnce();
            assertEquals(2, launcher.launched.size());
            assertEquals(1, launcher.jobs.get(1).scales.get());
            assertEquals(0, launcher.jobs.get(1).cancels.get());

            // desired stop -> cancel + confirmed state
            plane.stop("jobA", "op");
            agent.reconcileOnce();
            assertEquals(1, launcher.jobs.get(1).cancels.get());
            assertEquals(JobState.DESIRED_STOPPED, plane.status("jobA").getState());
            assertTrue(agent.localJobs().isEmpty());

            // resume -> redeployed
            plane.resume("jobA", "op");
            agent.reconcileOnce();
            assertEquals(3, launcher.launched.size());
            assertEquals(JobState.RUNNING, plane.status("jobA").getState());

            agent.close();
            assertEquals(1, launcher.jobs.get(2).cancels.get());
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void twoAgentsArbitrateViaClaimAndFailureRetries() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return;
        }
        RedissonClient redis = createClient();
        String prefix = "it-agent-" + UUID.randomUUID().toString().substring(0, 8) + ":";
        try {
            RedisJobControlPlane plane = new RedisJobControlPlane(redis, prefix,
                    ControlPlaneAuthorizer.allowAll(), 100, 10);
            plane.submit(spec("jobA", Map.of(), 1), "op");

            RecordingLauncher launcherA = new RecordingLauncher();
            RecordingLauncher launcherB = new RecordingLauncher();
            JobAgent agentA = new JobAgent(plane, launcherA, redis, "inst-A",
                    Duration.ofSeconds(1), prefix + "claim:");
            JobAgent agentB = new JobAgent(plane, launcherB, redis, "inst-B",
                    Duration.ofSeconds(1), prefix + "claim:");

            // A fails its launch; claim is released, so B can pick the job up
            launcherA.failNextLaunch();
            agentA.reconcileOnce();
            assertEquals(JobState.FAILED, plane.status("jobA").getState());
            assertEquals(0, launcherA.launched.size());

            agentB.reconcileOnce();
            assertEquals(1, launcherB.launched.size());
            assertEquals("inst-B", plane.status("jobA").getInstanceId());
            assertEquals(JobState.RUNNING, plane.status("jobA").getState());

            // B owns the job: A must not redeploy while status stays RUNNING
            agentA.reconcileOnce();
            assertEquals(0, launcherA.launched.size());
            assertEquals(1, launcherB.launched.size());

            agentB.close();
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }
}
