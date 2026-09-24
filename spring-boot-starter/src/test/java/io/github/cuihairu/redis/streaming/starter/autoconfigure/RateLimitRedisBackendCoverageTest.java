package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiterRegistry;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/** Covers redis-backend and leaky-bucket branches of buildOneRaw via the public registry API. */
class RateLimitRedisBackendCoverageTest {

    private final RedisStreamingRateLimitAutoConfiguration cfg = new RedisStreamingRateLimitAutoConfiguration();

    private static RedisStreamingProperties.RateLimitProperties.Policy policy(String algo, String backend) {
        RedisStreamingProperties.RateLimitProperties.Policy p =
                new RedisStreamingProperties.RateLimitProperties.Policy();
        p.setAlgorithm(algo);
        p.setBackend(backend);
        p.setCapacity(10);
        p.setRatePerSecond(5.0);
        p.setWindowMs(1000);
        p.setLimit(5);
        p.setKeyPrefix("rl-test");
        return p;
    }

    @Test
    void redisBackendConstructsRedisLimiters() {
        RedissonClient redisson = mock(RedissonClient.class);
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRatelimit().setEnabled(true);
        props.getRatelimit().getPolicies().put("redis-sliding", policy("sliding", "redis"));
        props.getRatelimit().getPolicies().put("redis-token", policy("token-bucket", "redis"));
        props.getRatelimit().setDefaultName("redis-sliding");

        RateLimiterRegistry registry = cfg.rateLimiterRegistry(props, redisson);
        assertNotNull(registry.get("redis-sliding"));
        assertNotNull(registry.get("redis-token"));

        RateLimiter limiter = cfg.rateLimiter(registry, props);
        assertNotNull(limiter);
    }

    @Test
    void redisBackendWithoutClientFallsBackToMemory() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRatelimit().setEnabled(true);
        props.getRatelimit().getPolicies().put("fallback-sliding", policy("sliding", "redis"));
        props.getRatelimit().getPolicies().put("fallback-token", policy("token-bucket", "redis"));
        props.getRatelimit().setDefaultName("fallback-sliding");

        RateLimiterRegistry registry = cfg.rateLimiterRegistry(props, null);
        assertNotNull(registry.get("fallback-sliding"));
        assertNotNull(registry.get("fallback-token"));
    }

    @Test
    void leakyBucketAlgorithmIsSupported() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRatelimit().setEnabled(true);
        props.getRatelimit().getPolicies().put("leaky", policy("leaky-bucket", "memory"));
        props.getRatelimit().setDefaultName("leaky");

        RateLimiterRegistry registry = cfg.rateLimiterRegistry(props, null);
        assertNotNull(registry.get("leaky"));
        assertNotNull(cfg.rateLimiter(registry, props));
    }
}
