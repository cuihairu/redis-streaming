package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiterRegistry;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

public class RateLimitAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RateLimitOnlyConfig.class);

    @Test
    public void testDefaultSlidingInMemoryRateLimiter() {
        runner.withPropertyValues(
                        "redis-streaming.ratelimit.enabled=true",
                        "redis-streaming.ratelimit.window-ms=1000",
                        "redis-streaming.ratelimit.limit=1"
                )
                .run(ctx -> {
                    RateLimiter rl = ctx.getBean(RateLimiter.class);
                    assertTrue(rl.allowAt("k", 1000));
                    assertFalse(rl.allowAt("k", 1000));
                });
    }

    @Test
    public void testPoliciesTokenBucketAndLeakyBucket() {
        runner.withPropertyValues(
                        "redis-streaming.ratelimit.enabled=true",
                        "redis-streaming.ratelimit.default-name=tb",

                        "redis-streaming.ratelimit.policies.tb.algorithm=token-bucket",
                        "redis-streaming.ratelimit.policies.tb.backend=memory",
                        "redis-streaming.ratelimit.policies.tb.capacity=1",
                        "redis-streaming.ratelimit.policies.tb.rate-per-second=1",

                        "redis-streaming.ratelimit.policies.lb.algorithm=leaky-bucket",
                        "redis-streaming.ratelimit.policies.lb.capacity=1",
                        "redis-streaming.ratelimit.policies.lb.rate-per-second=1"
                )
                .run(ctx -> {
                    RateLimiterRegistry reg = ctx.getBean(RateLimiterRegistry.class);
                    RateLimiter tb = reg.get("tb");
                    RateLimiter lb = reg.get("lb");
                    assertNotNull(tb);
                    assertNotNull(lb);

                    assertTrue(tb.allowAt("k", 0));
                    assertFalse(tb.allowAt("k", 0));
                    assertTrue(tb.allowAt("k", 1000));

                    assertTrue(lb.allowAt("k", 0));
                    assertFalse(lb.allowAt("k", 0));
                    assertTrue(lb.allowAt("k", 1000));
                });
    }

    @Test
    public void testDefaultNameMissingFallsBackToBuiltinWhenRegistryEmpty() {
        new ApplicationContextRunner()
                .withUserConfiguration(RateLimitOnlyConfig.class)
                .withBean(RateLimiterRegistry.class, () -> new RateLimiterRegistry(java.util.Map.of()))
                .withPropertyValues(
                        "redis-streaming.ratelimit.enabled=true",
                        "redis-streaming.ratelimit.default-name=missing"
                )
                .run(ctx -> {
                    RateLimiter rl = ctx.getBean(RateLimiter.class);
                    assertTrue(rl.allowAt("k", 0));
                    assertTrue(rl.allowAt("k", 0));
                });
    }

    @Test
    public void testRedisBackendWithoutRedissonFallsBackToInMemory() {
        runner.withPropertyValues(
                        "redis-streaming.ratelimit.enabled=true",
                        "redis-streaming.ratelimit.backend=redis",
                        "redis-streaming.ratelimit.window-ms=1000",
                        "redis-streaming.ratelimit.limit=1"
                )
                .run(ctx -> {
                    RateLimiter rl = ctx.getBean(RateLimiter.class);
                    assertTrue(rl.allowAt("k", 0));
                    assertFalse(rl.allowAt("k", 0));
                });
    }

    @Test
    public void testUnknownAlgorithmDefaultsToSliding() {
        runner.withPropertyValues(
                        "redis-streaming.ratelimit.enabled=true",
                        "redis-streaming.ratelimit.policies.p1.algorithm=weird",
                        "redis-streaming.ratelimit.policies.p1.limit=1",
                        "redis-streaming.ratelimit.policies.p1.window-ms=1000",
                        "redis-streaming.ratelimit.default-name=p1"
                )
                .run(ctx -> {
                    RateLimiter rl = ctx.getBean(RateLimiter.class);
                    assertTrue(rl.allowAt("k", 0));
                    assertFalse(rl.allowAt("k", 0));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RedisStreamingProperties.class)
    @Import(RedisStreamingAutoConfiguration.RateLimitConfiguration.class)
    static class RateLimitOnlyConfig {}
}
