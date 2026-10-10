package io.github.cuihairu.redis.streaming.runtime.redis.control;

import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;

/**
 * Builds the pipelines of a control-plane job into a fresh environment.
 *
 * <p>Pipeline graphs are lambdas and cannot be persisted, so a {@link JobSpec}
 * references its factory by name; execution side agents resolve the factory from
 * their local registry (registered via {@code JobLauncher.registerFactory}) and
 * interpret {@link JobSpec#getConfig() the spec config map} however they like.</p>
 */
@FunctionalInterface
public interface JobPipelineFactory {

    /**
     * Register all pipelines of the job on the (fresh, empty) environment.
     *
     * @param spec the deployed spec (jobName/parallelism/config)
     * @param env  environment to add pipelines to; the agent calls {@code executeAsync()} afterwards
     */
    void build(JobSpec spec, RedisStreamExecutionEnvironment env);
}
