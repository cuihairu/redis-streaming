package io.github.cuihairu.redis.streaming.runtime.redis.control;

import io.github.cuihairu.redis.streaming.runtime.redis.RedisJobClient;

/**
 * Starts a job locally from its declarative spec. The control plane stores desired
 * state; a {@link JobLauncher} owns the "how" of in-process execution (environment
 * construction, factory lookup, async start). {@link JobAgent} calls it on deploy
 * and on every full upgrade.
 */
@FunctionalInterface
public interface JobLauncher {

    /**
     * Build and start the job described by the spec.
     *
     * @return the running job handle
     * @throws Exception when the pipeline cannot be built or started (reported as {@link JobState#FAILED})
     */
    RedisJobClient launch(JobSpec spec) throws Exception;

    /**
     * @return true when {@code previous} and {@code next} differ only in fields the
     *         running job can absorb without a restart (currently: parallelism via
     *         {@code scaleParallelism}); used by the agent to pick the fast path.
     */
    default boolean isParallelismOnlyChange(JobSpec previous, JobSpec next) {
        return previous.getPipelineFactory().equals(next.getPipelineFactory())
                && java.util.Objects.equals(previous.getConfig(), next.getConfig());
    }
}
