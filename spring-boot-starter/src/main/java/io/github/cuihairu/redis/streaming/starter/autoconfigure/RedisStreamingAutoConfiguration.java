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
        RedisStreamingRateLimitAutoConfiguration.class,
        RedisStreamingRuntimeAutoConfiguration.class
})
public class RedisStreamingAutoConfiguration {

    /**
     * Create RedissonClient
     *
     * Note: If the project already has redisson-spring-boot-starter configured,
     * this bean will be skipped (@ConditionalOnMissingBean) and the project's RedissonClient configuration will be used.
     *
     * This provides simplified single-server configuration, suitable for quick development and testing.
     * For production, it is recommended to use redisson-spring-boot-starter for full cluster/sentinel/SSL configuration,
     * or register a {@link ConfigCustomizer} bean to reach TLS/cluster/sentinel settings
     * (docs/Security-Hardening-Design.md 方案 A).
     */
    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("deprecation") // setUsername/setPassword replaced by CredentialsResolver in Redisson 4.x; still functional
    public RedissonClient redissonClient(RedisStreamingProperties properties,
                                         org.springframework.beans.factory.ObjectProvider<ConfigCustomizer> customizers) {
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
        String username = resolveCredential(redis.getUsername());
        if (username != null && !username.isBlank()) {
            serverConfig.setUsername(username);
        }
        String password = resolveCredential(redis.getPassword());
        if (password != null && !password.isBlank()) {
            serverConfig.setPassword(password);
        }

        // user hooks run last so they can override everything above (TLS, codecs, ...)
        customizers.orderedStream().forEach(c -> c.customize(config));

        log.info("Initializing RedissonClient with address: {} (Simple single-server mode)", redis.getAddress());
        log.info("For production with cluster/sentinel, use redisson-spring-boot-starter");
        return Redisson.create(config);
    }

    /**
     * Resolve a {@code ${env:VAR}} credential placeholder against the process
     * environment so secrets stay out of config files. Any other value passes
     * through unchanged; a missing variable fails fast with a clear message
     * instead of surfacing later as a confusing auth error.
     */
    static String resolveCredential(String raw) {
        return resolveCredential(raw, System::getenv);
    }

    static String resolveCredential(String raw, java.util.function.UnaryOperator<String> env) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("${env:") && trimmed.endsWith("}")) {
            String name = trimmed.substring("${env:".length(), trimmed.length() - 1);
            String value = env.apply(name);
            if (value == null) {
                throw new IllegalStateException(
                        "Environment variable '" + name + "' referenced by ${env:" + name + "} is not set");
            }
            return value;
        }
        return raw;
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
