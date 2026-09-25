package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.reliability.ratelimit.InMemoryLeakyBucketRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.InMemorySlidingWindowRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.InMemoryTokenBucketRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiterRegistry;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Residual coverage for the rate-limit auto-configuration: algorithm defaulting, the
 * missing-policies fallback and the default-limiter fallback in {@code rateLimiter(...)}.
 */
class RateLimitAutoConfigurationResidualCoverage2Test {

    private static RateLimiter buildOne(String name, RedisStreamingProperties.RateLimitProperties.Policy policy)
            throws Exception {
        Method m = RedisStreamingRateLimitAutoConfiguration.class.getDeclaredMethod(
                "buildOneRaw", String.class, RedisStreamingProperties.RateLimitProperties.Policy.class,
                org.redisson.api.RedissonClient.class);
        m.setAccessible(true);
        return (RateLimiter) m.invoke(new RedisStreamingRateLimitAutoConfiguration(), name, policy, null);
    }

    @Test
    void nullOrBlankAlgorithmDefaultsToSliding() throws Exception {
        RedisStreamingProperties.RateLimitProperties.Policy policy =
                new RedisStreamingProperties.RateLimitProperties.Policy();

        policy.setAlgorithm(null);
        assertInstanceOf(InMemorySlidingWindowRateLimiter.class, buildOne("p", policy));

        policy.setAlgorithm("   ");
        assertInstanceOf(InMemorySlidingWindowRateLimiter.class, buildOne("p", policy));

        policy.setAlgorithm("token-bucket");
        assertInstanceOf(InMemoryTokenBucketRateLimiter.class, buildOne("p", policy));

        policy.setAlgorithm("leaky-bucket");
        assertInstanceOf(InMemoryLeakyBucketRateLimiter.class, buildOne("p", policy));

        policy.setAlgorithm("something-else");
        assertInstanceOf(InMemorySlidingWindowRateLimiter.class, buildOne("p", policy));
    }

    @Test
    void registryFallsBackToTopLevelPolicyWithoutNamedPolicies() {
        RedisStreamingRateLimitAutoConfiguration cfg = new RedisStreamingRateLimitAutoConfiguration();
        RedisStreamingProperties props = new RedisStreamingProperties();

        props.getRatelimit().setPolicies(null);
        RateLimiterRegistry fromNull = cfg.rateLimiterRegistry(props, null);
        assertNotNull(fromNull.get("default"));

        props.getRatelimit().setPolicies(new HashMap<>());
        RateLimiterRegistry fromEmpty = cfg.rateLimiterRegistry(props, null);
        assertNotNull(fromEmpty.get("default"));

        RedisStreamingProperties.RateLimitProperties.Policy named =
                new RedisStreamingProperties.RateLimitProperties.Policy();
        named.setAlgorithm("token-bucket");
        Map<String, RedisStreamingProperties.RateLimitProperties.Policy> policies = new HashMap<>();
        policies.put("strict", named);
        props.getRatelimit().setPolicies(policies);
        RateLimiterRegistry fromNamed = cfg.rateLimiterRegistry(props, null);
        assertNotNull(fromNamed.get("strict"));
        assertNotNull(fromNamed.get("default"), "default name is added when missing");
    }

    @Test
    void defaultLimiterFallsBackWhenDefaultNameUnregistered() throws Exception {
        RedisStreamingRateLimitAutoConfiguration cfg = new RedisStreamingRateLimitAutoConfiguration();
        RedisStreamingProperties props = new RedisStreamingProperties();

        RateLimiter fallback = cfg.rateLimiter(new RateLimiterRegistry(Map.of()), props);
        assertInstanceOf(InMemorySlidingWindowRateLimiter.class, fallback);

        RateLimiter available = buildOne("other", new RedisStreamingProperties.RateLimitProperties.Policy());
        RateLimiter picked = cfg.rateLimiter(new RateLimiterRegistry(Map.of("other", available)), props);
        assertSame(available, picked);
    }
}
