package io.github.cuihairu.redis.streaming.runtime.redis.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the interface-level default of the tenant-qualified lookup: an implementation that
 * does not specialize {@code get(tenant, jobName)} falls back to the name-only lookup.
 * ({@link RedisJobControlPlane} overrides the pair with a real tenant-scoped read; that
 * behavior is pinned in {@code RedisJobControlPlaneTenantTest}.)
 */
class JobControlPlaneDefaultsTest {

    private static JobSpec spec(String name) {
        return JobSpec.builder().jobName(name).build();
    }

    @Test
    void tenantQualifiedLookupsDefaultToNameOnlyOnes() {
        JobSpec stored = spec("job-a");
        JobStatus[] reported = new JobStatus[1];
        JobState[] reportedState = new JobState[1];
        JobControlPlane plane = new JobControlPlane() {
            @Override
            public JobSpec submit(JobSpec jobSpec, String actor) {
                return jobSpec;
            }

            @Override
            public JobSpec get(String jobName) {
                return "job-a".equals(jobName) ? stored : null;
            }

            @Override
            public java.util.List<JobSpec> list() {
                return java.util.List.of(stored);
            }

            @Override
            public JobSpec upgrade(String jobName, java.util.function.UnaryOperator<JobSpec> mutator, String actor) {
                return mutator.apply(get(jobName));
            }

            @Override
            public JobSpec rollback(String jobName, String actor) {
                return get(jobName);
            }

            @Override
            public void stop(String jobName, String actor) {
            }

            @Override
            public void resume(String jobName, String actor) {
            }

            @Override
            public void reportStatus(String jobName, JobState state, String instanceId, String detail) {
                reportedState[0] = state;
                reported[0] = new JobStatus();
                reported[0].setState(state);
            }

            @Override
            public JobStatus status(String jobName) {
                return reported[0];
            }

            @Override
            public java.util.List<AuditEntry> tailAudit(int limit) {
                return java.util.List.of();
            }
        };

        // tenant-qualified reads fall through to the name-only lookups
        assertSame(stored, plane.get("tenant-1", "job-a"));
        assertNull(plane.get("tenant-1", "missing"));
        assertNull(plane.status("tenant-1", "job-a"));

        // tenant-qualified report falls through to the name-only write
        plane.reportStatus("tenant-1", "job-a", JobState.RUNNING, "i-1", null);
        assertSame(JobState.RUNNING, reportedState[0]);
        assertSame(reported[0], plane.status("tenant-1", "job-a"));
    }
}
