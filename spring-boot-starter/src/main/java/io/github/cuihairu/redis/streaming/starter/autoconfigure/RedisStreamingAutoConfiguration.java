package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.reliability.metrics.RateLimitMetrics;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Redis Streaming framework core auto-configuration.
 *
 * <p>Owns the shared {@link RedissonClient} bean and imports the feature-scoped
 * auto-configurations (registry, discovery, config-center, mq, rate-limit). Each feature
 * lives in its own {@code RedisStreaming*AutoConfiguration} class with an independent
 * {@code redis-streaming.<feature>.enabled} property.</p>
 *
 * <p>If the project already configures a {@code RedissonClient} (e.g. via
 * redisson-spring-boot-starter), this bean is skipped
 * ({@code @ConditionalOnMissingBean}) and the project's client is used.</p>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass({RedissonClient.class})
@EnableConfigurationProperties(RedisStreamingProperties.class)
@Import({
        RedisStreamingRegistryAutoConfiguration.class,
        RedisStreamingDiscoveryAutoConfiguration.class,
        RedisStreamingConfigServiceAutoConfiguration.class,
        RedisStreamingMqAutoConfiguration.class,
        RedisStreamingRateLimitAutoConfiguration.class
})
public class RedisStreamingAutoConfiguration {

    /**
     * Create RedissonClient
     *
     * Note: If the project already has redisson-spring-boot-starter configured,
     * this bean will be skipped (@ConditionalOnMissingBean) and the project's RedissonClient configuration will be used.
     *
     * This provides simplified single-server configuration, suitable for quick development and testing.
     * For production, it is recommended to use redisson-spring-boot-starter for full cluster/sentinel/SSL configuration.
     */
    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("deprecation") // setUsername/setPassword replaced by CredentialsResolver in Redisson 4.x; still functional
    public RedissonClient redissonClient(RedisStreamingProperties properties) {
        Config config = new Config();
        RedisStreamingProperties.RedisProperties redis = properties.getRedis();

        // Simplified configuration, single-server mode only
        // For full configuration, please use redisson-spring-boot-starter
        org.redisson.config.SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress(redis.getAddress())
                .setDatabase(redis.getDatabase())
                .setConnectTimeout(redis.getConnectTimeout())
                .setTimeout(redis.getTimeout())
                .setConnectionPoolSize(redis.getConnectionPoolSize())
                .setConnectionMinimumIdleSize(redis.getConnectionMinimumIdleSize());
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            serverConfig.setPassword(redis.getPassword());
        }

        log.info("Initializing RedissonClient with address: {} (Simple single-server mode)", redis.getAddress());
        log.info("For production with cluster/sentinel, use redisson-spring-boot-starter");
        return Redisson.create(config);
    }

    // Wire RateLimit metrics to Micrometer if present
    @Bean
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnBean(io.micrometer.core.instrument.MeterRegistry.class)
    public io.github.cuihairu.redis.streaming.starter.metrics.RateLimitMicrometerCollector rateLimitMicrometerCollector(
            io.micrometer.core.instrument.MeterRegistry registry) {
        return new io.github.cuihairu.redis.streaming.starter.metrics.RateLimitMicrometerCollector(registry);
    }

    @Bean
    @ConditionalOnBean(io.github.cuihairu.redis.streaming.starter.metrics.RateLimitMicrometerCollector.class)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    public Object installRateLimitCollector(io.github.cuihairu.redis.streaming.starter.metrics.RateLimitMicrometerCollector collector) {
        RateLimitMetrics.setCollector(collector);
        return new Object();
    }
}
