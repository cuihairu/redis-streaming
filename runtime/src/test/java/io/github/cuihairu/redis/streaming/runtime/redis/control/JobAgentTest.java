package io.github.cuihairu.redis.streaming.runtime.redis.control;

import io.github.cuihairu.redis.streaming.runtime.redis.RedisJobClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link JobAgent} reconcile rules with a mocked control plane,
 * launcher and Redis: claim-serialized deploy, desired-stop cancel, hash-drift
 * upgrade, parallelism-only fast path, failure retry, and external removal.
 */
class JobAgentTest {

    private static final String CLAIM_PREFIX = "p:claim:";

    private JobControlPlane plane;
    private JobLauncher launcher;
    private RedissonClient redis;
    @SuppressWarnings("unchecked")
    private final RBucket<String> claimBucket = mock(RBucket.class);

    private JobAgent agent;

    @BeforeEach
    void setUp() {
        plane = mock(JobControlPlane.class);
        launcher = mock(JobLauncher.class);
        redis = mock(RedissonClient.class);
        when(redis.<String>getBucket(anyString(), any(Codec.class))).thenReturn(claimBucket);
        when(launcher.isParallelismOnlyChange(any(), any())).thenCallRealMethod();
        agent = new JobAgent(plane, launcher, redis, "inst-1", Duration.ofSeconds(5), CLAIM_PREFIX);
    }

    private JobSpec spec(String name, Map<String, String> config, int parallelism, String hash) {
        return JobSpec.builder()
                .jobName(name)
                .pipelineFactory("f")
                .config(config)
                .parallelism(parallelism)
                .version(1L)
                .specHash(hash)
                .build();
    }

    private void statusOf(String job, JobState state) {
        when(plane.status(job)).thenReturn(new JobStatus(state, "", "", 0L));
    }

    @Test
    void deploysPendingJobAndReportsRunning() throws Exception {
        JobSpec s = spec("j1", Map.of(), 1, "h1");
        when(plane.list()).thenReturn(List.of(s));
        statusOf("j1", JobState.PENDING_DEPLOY);
        when(claimBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        RedisJobClient job = mock(RedisJobClient.class);
        when(launcher.launch(s)).thenReturn(job);

        agent.reconcileOnce();

        verify(launcher).launch(s);
        verify(plane).reportStatus("j1", JobState.RUNNING, "inst-1", null);
        verify(claimBucket).delete();
        assertEquals(java.util.Set.of("j1"), agent.localJobs());
    }

    @Test
    void skipsDeployWhenClaimHeldElsewhere() throws Exception {
        JobSpec s = spec("j1", Map.of(), 1, "h1");
        when(plane.list()).thenReturn(List.of(s));
        statusOf("j1", JobState.PENDING_DEPLOY);
        when(claimBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(false);

        agent.reconcileOnce();

        verify(launcher, never()).launch(any());
        assertTrue(agent.localJobs().isEmpty());
    }

    @Test
    void doesNotRedeployRunningJobsOwnedElsewhere() throws Exception {
        JobSpec s = spec("j1", Map.of(), 1, "h1");
        when(plane.list()).thenReturn(List.of(s));
        statusOf("j1", JobState.RUNNING);

        agent.reconcileOnce();

        verify(claimBucket, never()).setIfAbsent(anyString(), any(Duration.class));
        verify(launcher, never()).launch(any());
    }

    @Test
    void claimRedisErrorSkipsCycleWithoutDeploying() throws Exception {
        JobSpec s = spec("j1", Map.of(), 1, "h1");
        when(plane.list()).thenReturn(List.of(s));
        statusOf("j1", JobState.PENDING_DEPLOY);
        when(claimBucket.setIfAbsent(anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        agent.reconcileOnce();

        verify(launcher, never()).launch(any());
    }

    @Test
    void cancelsLocalJobOnDesiredStopped() throws Exception {
        JobSpec s = spec("j1", Map.of(), 1, "h1");
        when(plane.list()).thenReturn(List.of(s));
        statusOf("j1", JobState.PENDING_DEPLOY);
        when(claimBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        RedisJobClient job = mock(RedisJobClient.class);
        when(launcher.launch(s)).thenReturn(job);
        agent.reconcileOnce();

        statusOf("j1", JobState.DESIRED_STOPPED);
        agent.reconcileOnce();

        verify(job).cancel();
        verify(plane).reportStatus("j1", JobState.DESIRED_STOPPED, "inst-1", null);
        assertTrue(agent.localJobs().isEmpty());
    }

    @Test
    void upgradesLocalJobOnHashDrift() throws Exception {
        JobSpec v1 = spec("j1", Map.of("k", "1"), 1, "h1");
        when(plane.list()).thenReturn(List.of(v1));
        statusOf("j1", JobState.PENDING_DEPLOY);
        when(claimBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        RedisJobClient old = mock(RedisJobClient.class);
        when(launcher.launch(v1)).thenReturn(old);
        agent.reconcileOnce();

        JobSpec v2 = spec("j1", Map.of("k", "2"), 1, "h2");
        when(plane.list()).thenReturn(List.of(v2));
        statusOf("j1", JobState.RUNNING);
        RedisJobClient next = mock(RedisJobClient.class);
        when(launcher.launch(v2)).thenReturn(next);
        agent.reconcileOnce();

        verify(old).cancel();
        verify(launcher).launch(v2);
        verify(next, never()).cancel();
        // RUNNING reported on deploy and again on upgrade
        verify(plane, times(2)).reportStatus("j1", JobState.RUNNING, "inst-1", null);
        assertEquals(java.util.Set.of("j1"), agent.localJobs());
    }

    @Test
    void parallelismOnlyDriftTakesScaleFastPath() throws Exception {
        JobSpec v1 = spec("j1", Map.of("k", "1"), 1, "h1");
        when(plane.list()).thenReturn(List.of(v1));
        statusOf("j1", JobState.PENDING_DEPLOY);
        when(claimBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        RedisJobClient job = mock(RedisJobClient.class);
        when(launcher.launch(v1)).thenReturn(job);
        agent.reconcileOnce();

        JobSpec v2 = spec("j1", Map.of("k", "1"), 3, "h2");
        when(plane.list()).thenReturn(List.of(v2));
        statusOf("j1", JobState.RUNNING);
        when(job.scaleParallelism(3)).thenReturn(true);
        agent.reconcileOnce();

        verify(launcher, times(1)).launch(any()); // no relaunch
        verify(job).scaleParallelism(3);
        verify(job, never()).cancel();
    }

    @Test
    void scaleFailureFallsBackToFullUpgrade() throws Exception {
        JobSpec v1 = spec("j1", Map.of("k", "1"), 1, "h1");
        when(plane.list()).thenReturn(List.of(v1));
        statusOf("j1", JobState.PENDING_DEPLOY);
        when(claimBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        RedisJobClient old = mock(RedisJobClient.class);
        when(launcher.launch(v1)).thenReturn(old);
        agent.reconcileOnce();

        JobSpec v2 = spec("j1", Map.of("k", "1"), 3, "h2");
        when(plane.list()).thenReturn(List.of(v2));
        statusOf("j1", JobState.RUNNING);
        when(old.scaleParallelism(3)).thenReturn(false); // fast path unavailable
        RedisJobClient next = mock(RedisJobClient.class);
        when(launcher.launch(v2)).thenReturn(next);
        agent.reconcileOnce();

        verify(old).cancel();
        verify(launcher).launch(v2);
    }

    @Test
    void launchFailureReportsFailedThenRetriesNextCycle() throws Exception {
        JobSpec s = spec("j1", Map.of(), 1, "h1");
        when(plane.list()).thenReturn(List.of(s));
        statusOf("j1", JobState.PENDING_DEPLOY);
        when(claimBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        when(launcher.launch(s)).thenThrow(new IllegalStateException("boom"));

        agent.reconcileOnce();

        verify(plane).reportStatus(eq("j1"), eq(JobState.FAILED), eq("inst-1"), anyString());
        verify(claimBucket).delete(); // claim released for retry
        assertTrue(agent.localJobs().isEmpty());

        // next cycle: same spec, launch now works (doReturn avoids invoking the
        // still-active throwing stub during re-stubbing)
        RedisJobClient job = mock(RedisJobClient.class);
        org.mockito.Mockito.doReturn(job).when(launcher).launch(s);
        agent.reconcileOnce();
        verify(launcher, times(2)).launch(s);
        verify(plane).reportStatus("j1", JobState.RUNNING, "inst-1", null);
    }

    @Test
    void removesLocalJobWhenSpecDeleted() throws Exception {
        JobSpec s = spec("j1", Map.of(), 1, "h1");
        when(plane.list()).thenReturn(List.of(s));
        statusOf("j1", JobState.PENDING_DEPLOY);
        when(claimBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        RedisJobClient job = mock(RedisJobClient.class);
        when(launcher.launch(s)).thenReturn(job);
        agent.reconcileOnce();

        when(plane.list()).thenReturn(List.of());
        agent.reconcileOnce();

        verify(job).cancel();
        assertTrue(agent.localJobs().isEmpty());
    }

    @Test
    void closeCancelsAllLocalJobs() throws Exception {
        JobSpec s = spec("j1", Map.of(), 1, "h1");
        when(plane.list()).thenReturn(List.of(s));
        statusOf("j1", JobState.PENDING_DEPLOY);
        when(claimBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        RedisJobClient job = mock(RedisJobClient.class);
        when(launcher.launch(s)).thenReturn(job);
        agent.reconcileOnce();

        agent.close();
        verify(job).cancel();
        assertTrue(agent.localJobs().isEmpty());
    }

    @Test
    void startStopRunsReconcileAndSurvivesPlaneErrors() throws Exception {
        when(plane.list()).thenThrow(new RuntimeException("plane down"));
        agent.start();
        Thread.sleep(300); // immediate first pass throws -> reconcileSafe swallows
        agent.start(); // idempotent
        agent.stop();
        agent.stop(); // idempotent
        agent.close();
        verify(plane, org.mockito.Mockito.atLeastOnce()).list();
    }

    @Test
    void upgradeProceedsWhenPreCheckpointFails() throws Exception {
        JobSpec v1 = spec("j1", Map.of("k", "1"), 1, "h1");
        when(plane.list()).thenReturn(List.of(v1));
        statusOf("j1", JobState.PENDING_DEPLOY);
        when(claimBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        RedisJobClient old = mock(RedisJobClient.class);
        org.mockito.Mockito.doThrow(new RuntimeException("checkpoint down"))
                .when(old).triggerCheckpointNow();
        when(launcher.launch(v1)).thenReturn(old);
        agent.reconcileOnce();

        JobSpec v2 = spec("j1", Map.of("k", "2"), 1, "h2");
        when(plane.list()).thenReturn(List.of(v2));
        statusOf("j1", JobState.RUNNING);
        RedisJobClient next = mock(RedisJobClient.class);
        when(launcher.launch(v2)).thenReturn(next);
        agent.reconcileOnce();

        verify(old).triggerCheckpointNow();
        verify(old).cancel();
        verify(plane, times(2)).reportStatus("j1", JobState.RUNNING, "inst-1", null);
    }

    @Test
    void upgradeFailureReportsFailedAndForgetsJob() throws Exception {
        JobSpec v1 = spec("j1", Map.of("k", "1"), 1, "h1");
        when(plane.list()).thenReturn(List.of(v1));
        statusOf("j1", JobState.PENDING_DEPLOY);
        when(claimBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        RedisJobClient old = mock(RedisJobClient.class);
        when(launcher.launch(v1)).thenReturn(old);
        agent.reconcileOnce();

        JobSpec v2 = spec("j1", Map.of("k", "2"), 1, "h2");
        when(plane.list()).thenReturn(List.of(v2));
        statusOf("j1", JobState.RUNNING);
        org.mockito.Mockito.doThrow(new IllegalStateException("relaunch boom"))
                .doReturn(mock(RedisJobClient.class)).when(launcher).launch(any());
        agent.reconcileOnce();

        verify(old).cancel();
        verify(plane).reportStatus(eq("j1"), eq(JobState.FAILED), eq("inst-1"), anyString());
        assertTrue(agent.localJobs().isEmpty());
    }

    @Test
    void claimsDisabledDeploysWithoutBucketInteraction() throws Exception {
        JobAgent plain = new JobAgent(plane, launcher, redis, "inst-1", Duration.ofSeconds(5), null);
        JobSpec s = spec("j1", Map.of(), 1, "h1");
        when(plane.list()).thenReturn(List.of(s));
        statusOf("j1", JobState.PENDING_DEPLOY);
        RedisJobClient job = mock(RedisJobClient.class);
        when(launcher.launch(s)).thenReturn(job);

        plain.reconcileOnce();

        verify(claimBucket, never()).setIfAbsent(anyString(), any(Duration.class));
        verify(claimBucket, never()).delete();
        verify(plane).reportStatus("j1", JobState.RUNNING, "inst-1", null);
    }

    @Test
    void claimReleaseFailureIsSwallowed() throws Exception {
        JobSpec s = spec("j1", Map.of(), 1, "h1");
        when(plane.list()).thenReturn(List.of(s));
        statusOf("j1", JobState.PENDING_DEPLOY);
        when(claimBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("delete down")).when(claimBucket).delete();
        RedisJobClient job = mock(RedisJobClient.class);
        when(launcher.launch(s)).thenReturn(job);

        agent.reconcileOnce();

        verify(plane).reportStatus("j1", JobState.RUNNING, "inst-1", null);
        assertEquals(java.util.Set.of("j1"), agent.localJobs());
    }

    @Test
    void cancelFailureIsSwallowedOnDesiredStop() throws Exception {
        JobSpec s = spec("j1", Map.of(), 1, "h1");
        when(plane.list()).thenReturn(List.of(s));
        statusOf("j1", JobState.PENDING_DEPLOY);
        when(claimBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        RedisJobClient job = mock(RedisJobClient.class);
        org.mockito.Mockito.doThrow(new RuntimeException("cancel down")).when(job).cancel();
        when(launcher.launch(s)).thenReturn(job);
        agent.reconcileOnce();

        statusOf("j1", JobState.DESIRED_STOPPED);
        agent.reconcileOnce();

        verify(plane).reportStatus("j1", JobState.DESIRED_STOPPED, "inst-1", null);
        assertTrue(agent.localJobs().isEmpty());
    }
}
