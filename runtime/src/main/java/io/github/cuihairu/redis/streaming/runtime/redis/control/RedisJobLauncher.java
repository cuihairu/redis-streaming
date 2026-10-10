package io.github.cuihairu.redis.streaming.runtime.redis.control;

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
 * <p>The spec config map is passed through to the factory verbatim — the launcher
 * does not interpret it. Factories that need extra {@link RedisRuntimeConfig} knobs
 * should wrap this launcher and build the environment themselves.</p>
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

    @Override
    public RedisJobClient launch(JobSpec spec) {
        JobPipelineFactory factory = factories.get(spec.getPipelineFactory());
        if (factory == null) {
            throw new IllegalStateException(
                    "No pipeline factory registered for '" + spec.getPipelineFactory() + "' (job " + spec.getJobName() + ")");
        }
        RedisRuntimeConfig config = RedisRuntimeConfig.builder()
                .jobName(spec.getJobName())
                .tenant(spec.getTenant())
                .pipelineParallelism(spec.getParallelism())
                .build();
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redissonClient, config);
        factory.build(spec, env);
        return env.executeAsync();
    }
}
