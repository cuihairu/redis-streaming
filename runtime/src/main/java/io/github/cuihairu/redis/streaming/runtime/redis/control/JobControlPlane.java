package io.github.cuihairu.redis.streaming.runtime.redis.control;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Control plane API: declarative job specs with submit / upgrade / rollback /
 * stop / resume, status reporting, pluggable authorization and an audit stream.
 *
 * <p>The control plane stores <em>desired state</em> in Redis. It does not run jobs;
 * execution side agents reconcile local processes against the stored specs (see
 * {@code docs/Control-Plane-Design.md}). Every mutating call goes through the
 * {@link ControlPlaneAuthorizer} and is audited; {@code actor} may be null/blank and
 * then falls back to the {@code user.name} system property.</p>
 */
public interface JobControlPlane {

    /**
     * Register a new job. The spec's version is reset to 1 and its hash computed here;
     * the submitted name must not exist yet.
     *
     * @return the stored spec (version 1)
     * @throws IllegalArgumentException when the spec is invalid or the job already exists
     */
    JobSpec submit(JobSpec spec, String actor);

    /**
     * @return the current spec, or null when the job does not exist.
     */
    JobSpec get(String jobName);

    /**
     * Tenant-qualified lookup (docs/Multi-Tenancy-Design.md, step 3): reads the given
     * tenant's spec store directly instead of resolving by name, which is ambiguous when
     * several tenants registered the same job name. Defaults to the name-only lookup.
     *
     * @param tenant tenant namespace of the spec to read
     * @return the tenant's spec, or null when absent
     */
    default JobSpec get(String tenant, String jobName) {
        return get(jobName);
    }

    /**
     * @return all current specs, ordered by job name.
     */
    List<JobSpec> list();

    /**
     * Apply a mutation and store it as a new version. The mutator receives the current
     * spec and returns the changed one; version/hash/by/time fields are overwritten by
     * the control plane. The write is a compare-and-set on the current version, so a
     * concurrent upgrade by another actor fails instead of silently overwriting.
     *
     * @return the stored new-version spec
     * @throws IllegalArgumentException when the job does not exist or the mutated spec is invalid
     * @throws IllegalStateException    when a concurrent write changed the version (retry the upgrade)
     */
    JobSpec upgrade(String jobName, UnaryOperator<JobSpec> mutator, String actor);

    /**
     * Redeploy the previous spec version as a new version: the last history entry
     * becomes the new desired spec (with an incremented version). Consecutive
     * rollbacks walk back through history one step per call.
     *
     * @throws IllegalArgumentException when the job does not exist
     * @throws IllegalStateException    when there is no history to roll back to,
     *                                  or a concurrent write changed the version
     */
    JobSpec rollback(String jobName, String actor);

    /**
     * Mark the job as desired-stopped (agents cancel their local instance).
     */
    void stop(String jobName, String actor);

    /**
     * Re-enable a stopped job (state returns to {@link JobState#PENDING_DEPLOY}).
     */
    void resume(String jobName, String actor);

    /**
     * @return the reported status, or null when the job has no status entry yet.
     */
    JobStatus status(String jobName);

    /**
     * Tenant-qualified status read: resolves unambiguously even when several tenants
     * registered the same job name. Defaults to the name-only lookup.
     */
    default JobStatus status(String tenant, String jobName) {
        return status(jobName);
    }

    /**
     * Report local execution status. Intended for agents, not for operators.
     */
    void reportStatus(String jobName, JobState state, String instanceId, String detail);

    /**
     * Tenant-qualified status report: resolves unambiguously even when several tenants
     * registered the same job name. Defaults to the name-only write.
     */
    default void reportStatus(String tenant, String jobName, JobState state, String instanceId, String detail) {
        reportStatus(jobName, state, instanceId, detail);
    }

    /**
     * Read the most recent audit entries, newest first.
     *
     * @param limit maximum number of entries (1..10000)
     */
    List<AuditEntry> tailAudit(int limit);
}
