package io.github.cuihairu.redis.streaming.runtime.redis.control;

import io.github.cuihairu.redis.streaming.runtime.redis.RedisJobClient;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Execution-side reconciler: polls the control plane's desired state and drives the
 * local process towards it (deploy new jobs, upgrade drifted specs, cancel stopped
 * or deleted ones). This is the K8s-controller model applied to in-process jobs —
 * the control plane never touches running jobs directly, it only writes specs.
 *
 * <p>Reconcile rules per spec:</p>
 * <ul>
 *   <li>not local + {@link JobState#PENDING_DEPLOY} (or FAILED retry) → claim the job
 *       ({@code SET NX EX} on {@code <prefix>claim:<job>}, serializes concurrent agents)
 *       → launch via the {@link JobLauncher} → report RUNNING; failure reports FAILED
 *       and releases the claim so the next cycle retries.</li>
 *   <li>not local + {@link JobState#RUNNING} → owned by another instance; skipped
 *       (no auto fail-over — an operator re-enables via {@code resume()}).</li>
 *   <li>{@link JobState#DESIRED_STOPPED} → cancel the local instance (if any) and
 *       confirm the state.</li>
 *   <li>local + spec hash drift → parallelism-only changes take the
 *       {@code scaleParallelism} fast path; anything else is a full upgrade
 *       (best-effort checkpoint → cancel → relaunch).</li>
 *   <li>local + spec deleted → cancel and forget.</li>
 * </ul>
 */
@Slf4j
public class JobAgent implements AutoCloseable {

    private final JobControlPlane plane;
    private final JobLauncher launcher;
    private final RedissonClient redisson;
    private final String instanceId;
    private final Duration pollInterval;
    private final String claimPrefix;

    private final Map<String, RedisJobClient> localJobs = new ConcurrentHashMap<>();
    private final Map<String, JobSpec> localSpecs = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    public JobAgent(JobControlPlane plane, JobLauncher launcher, RedissonClient redisson,
                    String instanceId, Duration pollInterval, String claimPrefix) {
        this.plane = Objects.requireNonNull(plane, "plane");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.pollInterval = pollInterval == null ? Duration.ofSeconds(5) : pollInterval;
        this.claimPrefix = claimPrefix;
    }

    /**
     * Start the periodic reconcile loop (first pass immediate).
     */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "job-agent-" + instanceId);
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::reconcileSafe, 0,
                pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Stop the reconcile loop; locally running jobs keep running.
     */
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * Stop the loop and cancel every locally running job.
     */
    @Override
    public synchronized void close() {
        stop();
        for (Map.Entry<String, RedisJobClient> e : localJobs.entrySet()) {
            cancelQuietly(e.getKey(), e.getValue());
        }
        localJobs.clear();
        localSpecs.clear();
    }

    /** @return job names currently held by this agent. */
    public Set<String> localJobs() {
        return new HashSet<>(localJobs.keySet());
    }

    /**
     * One reconcile pass over the control plane state. Public so tests (and ops
     * tooling) can step deterministically without waiting for the poll interval.
     */
    public void reconcileOnce() {
        List<JobSpec> specs = plane.list();
        Set<String> specNames = new HashSet<>();
        for (JobSpec spec : specs) {
            specNames.add(spec.getJobName());
        }
        // specs removed while we still hold the job
        for (String name : new HashSet<>(localJobs.keySet())) {
            if (!specNames.contains(name)) {
                log.info("Job {} removed from control plane; cancelling local instance", name);
                cancelQuietly(name, localJobs.remove(name));
                localSpecs.remove(name);
            }
        }
        for (JobSpec spec : specs) {
            reconcileSpec(spec);
        }
    }

    private void reconcileSafe() {
        try {
            reconcileOnce();
        } catch (Exception e) {
            log.warn("Job agent reconcile failed (instance={})", instanceId, e);
        }
    }

    private void reconcileSpec(JobSpec spec) {
        String name = spec.getJobName();
        String tenant = tenantOf(spec);
        JobStatus status = plane.status(tenant, name);
        boolean local = localJobs.containsKey(name);

        if (status != null && status.getState() == JobState.DESIRED_STOPPED) {
            if (local) {
                log.info("Job {} desired stopped; cancelling local instance", name);
                cancelQuietly(name, localJobs.remove(name));
                localSpecs.remove(name);
                plane.reportStatus(tenant, name, JobState.DESIRED_STOPPED, instanceId, null);
            }
            return;
        }

        if (!local) {
            if (status != null && status.getState() == JobState.RUNNING) {
                return; // owned by another instance; no auto fail-over
            }
            deployNew(spec);
            return;
        }

        JobSpec previous = localSpecs.get(name);
        if (previous != null && !Objects.equals(previous.getSpecHash(), spec.getSpecHash())) {
            if (launcher.isParallelismOnlyChange(previous, spec)) {
                RedisJobClient job = localJobs.get(name);
                if (job != null && job.scaleParallelism(spec.getParallelism())) {
                    log.info("Job {} parallelism {} -> {} via scale fast path", name, previous.getParallelism(), spec.getParallelism());
                    track(name, spec, job);
                    plane.reportStatus(tenantOf(spec), name, JobState.RUNNING, instanceId, null);
                    return;
                }
                log.info("Job {} scale fast path unavailable; falling back to full upgrade", name);
            }
            upgradeLocal(spec);
        }
    }

    private void deployNew(JobSpec spec) {
        String name = spec.getJobName();
        if (!tryClaim(spec)) {
            log.debug("Job {} claim held elsewhere; skipping", name);
            return;
        }
        try {
            RedisJobClient job = launcher.launch(spec);
            track(name, spec, job);
            plane.reportStatus(tenantOf(spec), name, JobState.RUNNING, instanceId, null);
            log.info("Job {} deployed (version {}, instance {})", name, spec.getVersion(), instanceId);
        } catch (Exception e) {
            log.warn("Job {} deploy failed on instance {}", name, instanceId, e);
            plane.reportStatus(tenantOf(spec), name, JobState.FAILED, instanceId, String.valueOf(e.getMessage()));
        } finally {
            releaseClaim(spec);
        }
    }

    private void upgradeLocal(JobSpec spec) {
        String name = spec.getJobName();
        RedisJobClient old = localJobs.get(name);
        try {
            if (old != null) {
                try {
                    old.triggerCheckpointNow();
                } catch (Exception e) {
                    log.debug("Pre-upgrade checkpoint failed for job {} (continuing)", name, e);
                }
                cancelQuietly(name, old);
            }
            RedisJobClient job = launcher.launch(spec);
            track(name, spec, job);
            plane.reportStatus(tenantOf(spec), name, JobState.RUNNING, instanceId, null);
            log.info("Job {} upgraded to version {} (instance {})", name, spec.getVersion(), instanceId);
        } catch (Exception e) {
            log.warn("Job {} upgrade failed on instance {}", name, instanceId, e);
            localJobs.remove(name);
            localSpecs.remove(name);
            plane.reportStatus(tenantOf(spec), name, JobState.FAILED, instanceId, String.valueOf(e.getMessage()));
        }
    }

    private void track(String name, JobSpec spec, RedisJobClient job) {
        localJobs.put(name, job);
        localSpecs.put(name, spec);
    }

    /** Normalized tenant of a spec ("default" when blank/unset). */
    private String tenantOf(JobSpec spec) {
        return io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.normalizeTenant(spec.getTenant());
    }

    /**
     * Claim key is tenant-scoped ({@code {prefix}[{tenant}:]{job}}) so two tenants can run
     * the same job name on different agents without stealing each other's claim. The
     * default tenant keeps the pre-tenant layout.
     */
    private String claimKey(JobSpec spec) {
        String tenant = tenantOf(spec);
        String segment = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.DEFAULT_TENANT.equals(tenant)
                ? "" : tenant + ":";
        return claimPrefix + segment + spec.getJobName();
    }

    private boolean tryClaim(JobSpec spec) {
        if (claimPrefix == null) {
            return true; // claims disabled (single-agent setups / tests)
        }
        try {
            RBucket<String> bucket = redisson.getBucket(claimKey(spec), StringCodec.INSTANCE);
            return bucket.setIfAbsent(instanceId, claimTtl());
        } catch (Exception e) {
            log.warn("Claim attempt failed for job {} (deploying anyway is unsafe; skipping cycle)", spec.getJobName(), e);
            return false;
        }
    }

    private void releaseClaim(JobSpec spec) {
        if (claimPrefix == null) {
            return;
        }
        try {
            redisson.getBucket(claimKey(spec), StringCodec.INSTANCE).delete();
        } catch (Exception e) {
            log.debug("Claim release failed for job {} (expires via TTL)", spec.getJobName(), e);
        }
    }

    private Duration claimTtl() {
        long ttlMs = Math.max(pollInterval.toMillis() * 3, 10_000L);
        return Duration.ofMillis(ttlMs);
    }

    private void cancelQuietly(String name, RedisJobClient job) {
        if (job == null) {
            return;
        }
        try {
            job.cancel();
        } catch (Exception e) {
            log.warn("Cancel failed for job {}", name, e);
        }
    }
}
