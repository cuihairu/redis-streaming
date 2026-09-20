package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiterRegistry;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Branch coverage for the rate-limit auto-configuration policy wiring (no Redis needed). */
class RateLimitPolicyBranchesTest {

    private final RedisStreamingRateLimitAutoConfiguration cfg = new RedisStreamingRateLimitAutoConfiguration();

    @Test
    void singlePolicyAndNamedPoliciesAndFallbacks() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRatelimit().setEnabled(true);
        props.getRatelimit().setBackend("memory");
        props.getRatelimit().setWindowMs(1000);
        props.getRatelimit().setLimit(5);

        RateLimiterRegistry registry = cfg.rateLimiterRegistry(props, null);
        assertNotNull(registry.get("default"));
        assertTrue(registry.all().containsKey("default"));

        RateLimiter limiter = cfg.rateLimiter(registry, props);
        assertNotNull(limiter);
        assertTrue(limiter.allowAt("k", System.currentTimeMillis()));

        // named policies, default missing from policies -> synthesized fallback
        props.getRatelimit().setDefaultName("missing-default");
        props.getRatelimit().getPolicies().put("api", policy("token-bucket", 10, 5.0));
        RateLimiterRegistry r2 = cfg.rateLimiterRegistry(props, null);
        assertNotNull(r2.get("api"));
        assertNotNull(r2.get("missing-default"));
        assertNotNull(cfg.rateLimiter(r2, props));

        // unknown algorithm falls back to sliding silently
        RedisStreamingProperties.RateLimitProperties.Policy bogus =
                new RedisStreamingProperties.RateLimitProperties.Policy();
        bogus.setAlgorithm("quantum-bucket");
        props.getRatelimit().getPolicies().put("weird", bogus);
        assertNotNull(cfg.rateLimiterRegistry(props, null).get("weird"));
    }

    private static RedisStreamingProperties.RateLimitProperties.Policy policy(String algo, int capacity, double rate) {
        RedisStreamingProperties.RateLimitProperties.Policy p =
                new RedisStreamingProperties.RateLimitProperties.Policy();
        p.setAlgorithm(algo);
        p.setBackend("memory");
        p.setCapacity(capacity);
        p.setRatePerSecond(rate);
        return p;
    }
}
