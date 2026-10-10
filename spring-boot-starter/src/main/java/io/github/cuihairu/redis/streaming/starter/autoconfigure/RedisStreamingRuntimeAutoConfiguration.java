package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.runtime.redis.control.ControlPlaneAuthorizer;
import io.github.cuihairu.redis.streaming.runtime.redis.control.JobAgent;
import io.github.cuihairu.redis.streaming.runtime.redis.control.JobLauncher;
import io.github.cuihairu.redis.streaming.runtime.redis.control.JobPipelineFactory;
import io.github.cuihairu.redis.streaming.runtime.redis.control.RedisJobControlPlane;
import io.github.cuihairu.redis.streaming.runtime.redis.control.RedisJobLauncher;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Runtime job control plane auto-configuration: exposes a Redis-backed
 * {@link RedisJobControlPlane} bean and (opt-in) a {@link JobAgent} reconciler
 * that deploys/upgrade/stops jobs declared on the control plane.
 *
 * <p>Both features are opt-in via properties:</p>
 * <ul>
 *   <li>{@code redis-streaming.runtime.control-plane.enabled=true} — spec store + audit</li>
 *   <li>{@code redis-streaming.runtime.agent.enabled=true} — reconcile loop
 *       (starts immediately, closes (cancelling local jobs) on context shutdown)</li>
 * </ul>
 *
 * <p>{@link ControlPlaneAuthorizer} and {@link JobLauncher} beans defined by the
 * application win over the defaults ({@code @ConditionalOnMissingBean}). Pipeline
 * factories are {@link JobPipelineFactory} beans registered on the default launcher
 * under their Spring bean name — {@code JobSpec.pipelineFactory} must match it.</p>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass({RedissonClient.class, RedisJobControlPlane.class})
@EnableConfigurationProperties(RedisStreamingProperties.class)
@ConditionalOnProperty(prefix = "redis-streaming.runtime.control-plane", name = "enabled", havingValue = "true")
public class RedisStreamingRuntimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ControlPlaneAuthorizer controlPlaneAuthorizer() {
        return ControlPlaneAuthorizer.allowAll();
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisJobControlPlane jobControlPlane(RedissonClient redissonClient,
                                                ControlPlaneAuthorizer authorizer,
                                                RedisStreamingProperties properties) {
        var p = properties.getRuntime().getControlPlane();
        log.info("Initializing RedisJobControlPlane with prefix: {}", p.getPrefix());
        return new RedisJobControlPlane(redissonClient, p.getPrefix(), authorizer,
                p.getAuditMaxEntries(), p.getHistoryMaxEntries());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "redis-streaming.runtime.agent", name = "enabled", havingValue = "true")
    public JobLauncher jobLauncher(RedissonClient redissonClient, ApplicationContext context) {
        RedisJobLauncher launcher = new RedisJobLauncher(redissonClient);
        // factories are keyed by Spring bean name: JobSpec.pipelineFactory must match it
        for (String name : context.getBeanNamesForType(JobPipelineFactory.class)) {
            JobPipelineFactory factory = context.getBean(name, JobPipelineFactory.class);
            launcher.registerFactory(name, factory);
            log.info("Registered job pipeline factory '{}' -> {}", name, factory.getClass().getName());
        }
        return launcher;
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "redis-streaming.runtime.agent", name = "enabled", havingValue = "true")
    public JobAgent jobAgent(RedisJobControlPlane controlPlane,
                             JobLauncher launcher,
                             RedissonClient redissonClient,
                             ApplicationContext context,
                             RedisStreamingProperties properties) {
        var p = properties.getRuntime().getAgent();
        String instanceId = p.getInstanceId() == null || p.getInstanceId().isBlank()
                ? generatedInstanceId()
                : p.getInstanceId();
        String claimPrefix;
        if (p.getClaimPrefix() == null) {
            claimPrefix = controlPlane.prefix() + "claim:";
        } else {
            claimPrefix = p.getClaimPrefix().isBlank() ? null : p.getClaimPrefix();
        }
        log.info("Starting JobAgent (instance={}, pollInterval={}, claims={})",
                instanceId, p.getPollInterval(), claimPrefix == null ? "disabled" : claimPrefix);
        return new JobAgent(controlPlane, launcher, redissonClient, instanceId, p.getPollInterval(), claimPrefix);
    }

    private String generatedInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "agent";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
