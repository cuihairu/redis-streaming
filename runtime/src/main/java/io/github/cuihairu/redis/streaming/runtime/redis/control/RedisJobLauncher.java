package io.github.cuihairu.redis.streaming.runtime.redis.control;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisJobClient;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import org.redisson.api.RedissonClient;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link JobLauncher}: creates a {@link RedisStreamExecutionEnvironment}
 * for the spec (jobName + parallelism), delegates pipeline construction to the
 * {@link JobPipelineFactory} registered under {@link JobSpec#getPipelineFactory()},
 * and starts the job with {@code executeAsync()}.
 *
 * <p>The spec config map is passed through to the factory with {@code secret.*} entries
 * resolved to their referenced material ({@link SecretRefs}; a spec without secret keys is
 * handed over unchanged) — the launcher does not otherwise interpret it. A failed secret
 * resolution fails the launch and is reported as {@link JobState#FAILED} by the agent.
 * Factories that need extra {@link RedisRuntimeConfig} knobs should wrap this launcher and
 * build the environment themselves.</p>
 */
public class RedisJobLauncher implements JobLauncher {

    private final RedissonClient redissonClient;
    private final Map<String, JobPipelineFactory> factories = new ConcurrentHashMap<>();

    public RedisJobLauncher(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * Register (or replace) the factory for a name. Must be called before the agent
     * deploys a spec referencing that factory.
     */
    public void registerFactory(String name, JobPipelineFactory factory) {
        factories.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(factory, "factory"));
    }

    /**
     * Runtime config for a spec: job identity, tenant, parallelism, and the declared
     * per-consumer in-flight cap ({@code JobSpec.maxInFlight}) passed through to the
     * consumer memory gate. The job's tenant is stamped onto the options by
     * {@link RedisRuntimeConfig}, so producers/consumers derive tenant-scoped keys from it.
     */
    static RedisRuntimeConfig configFor(JobSpec spec) {
        return RedisRuntimeConfig.builder()
                .jobName(spec.getJobName())
                .tenant(spec.getTenant())
                .pipelineParallelism(spec.getParallelism())
                .mqOptions(MqOptions.builder().maxInFlight(spec.getMaxInFlight()).build())
                .build();
    }

    @Override
    public RedisJobClient launch(JobSpec spec) {
        JobPipelineFactory factory = factories.get(spec.getPipelineFactory());
        if (factory == null) {
            throw new IllegalStateException(
                    "No pipeline factory registered for '" + spec.getPipelineFactory() + "' (job " + spec.getJobName() + ")");
        }
        RedisRuntimeConfig config = configFor(spec);
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redissonClient, config);
        factory.build(SecretRefs.resolvedSpec(spec), env);
        return env.executeAsync();
    }
}
