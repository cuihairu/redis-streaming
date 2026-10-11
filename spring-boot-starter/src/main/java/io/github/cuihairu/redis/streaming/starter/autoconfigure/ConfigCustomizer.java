package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import org.redisson.config.Config;

/**
 * Hook to adjust the Redisson {@link Config} just before the client is created
 * (docs/Security-Hardening-Design.md 方案 A).
 *
 * <p>This is the supported path for everything the simplified single-server
 * properties deliberately do not mirror: TLS ({@code rediss://}, truststore,
 * hostname verification), cluster/sentinel topology, read/write splitting,
 * custom codecs, netty options, and so on. The framework does not replicate
 * Redisson's configuration surface property by property.</p>
 *
 * <p>Register any number of beans of this type; they run in {@code @Order}
 * after the built-in single-server settings and before {@code Redisson.create}.
 * The hook only fires for the framework-owned client (it is skipped entirely
 * when the project provides its own {@code RedissonClient} bean).</p>
 */
@FunctionalInterface
public interface ConfigCustomizer {

    /**
     * Mutate the config in place. Must not return a different instance and must
     * not create a client itself.
     *
     * @param config the pending Redisson configuration
     */
    void customize(Config config);
}
