package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.InMemoryLeakyBucketRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.InMemorySlidingWindowRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.InMemoryTokenBucketRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.NamedRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RedisSlidingWindowRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RedisTokenBucketRateLimiter;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Rate-limiting scoped auto-configuration beans (extracted from {@link RedisStreamingAutoConfiguration}).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "redis-streaming.ratelimit", name = "enabled", havingValue = "true")
public class RedisStreamingRateLimitAutoConfiguration {

    private RateLimiter buildOneRaw(String name, RedisStreamingProperties.RateLimitProperties.Policy p, RedissonClient redissonClient) {
        String backend = p.getBackend();
        String algorithm = p.getAlgorithm();
        if (algorithm == null || algorithm.isBlank()) algorithm = "sliding";

        switch (algorithm.toLowerCase()) {
            case "sliding": {
                if ("redis".equalsIgnoreCase(backend)) {
                    if (redissonClient != null) {
                        log.info("RateLimiter[{}]=RedisSlidingWindow(windowMs={}, limit={}, keyPrefix={})", name, p.getWindowMs(), p.getLimit(), p.getKeyPrefix());
                        return new RedisSlidingWindowRateLimiter(redissonClient, p.getKeyPrefix(), p.getWindowMs(), p.getLimit());
                    }
                    log.warn("RateLimiter[{}] redis backend requested but no RedissonClient; using in-memory sliding.", name);
                }
                log.info("RateLimiter[{}]=InMemorySlidingWindow(windowMs={}, limit={})", name, p.getWindowMs(), p.getLimit());
                return new InMemorySlidingWindowRateLimiter(p.getWindowMs(), p.getLimit());
            }
            case "token-bucket": {
                if ("redis".equalsIgnoreCase(backend)) {
                    if (redissonClient != null) {
                        log.info("RateLimiter[{}]=RedisTokenBucket(capacity={}, rate/s={}, keyPrefix={})", name, p.getCapacity(), p.getRatePerSecond(), p.getKeyPrefix());
                        return new RedisTokenBucketRateLimiter(redissonClient, p.getKeyPrefix(), p.getCapacity(), p.getRatePerSecond());
                    }
                    log.warn("RateLimiter[{}] redis backend requested but no RedissonClient; using in-memory token-bucket.", name);
                }
                log.info("RateLimiter[{}]=InMemoryTokenBucket(capacity={}, rate/s={})", name, p.getCapacity(), p.getRatePerSecond());
                return new InMemoryTokenBucketRateLimiter(p.getCapacity(), p.getRatePerSecond());
            }
            case "leaky-bucket": {
                log.info("RateLimiter[{}]=InMemoryLeakyBucket(capacity={}, leak/s={})", name, p.getCapacity(), p.getRatePerSecond());
                return new InMemoryLeakyBucketRateLimiter(p.getCapacity(), p.getRatePerSecond());
            }
            default:
                log.warn("RateLimiter[{}] unknown algorithm '{}', defaulting to sliding.", name, algorithm);
                return new InMemorySlidingWindowRateLimiter(p.getWindowMs(), p.getLimit());
        }
    }

    private RateLimiter buildOne(String name, RedisStreamingProperties.RateLimitProperties.Policy p, RedissonClient redissonClient) {
        return new NamedRateLimiter(name, buildOneRaw(name, p, redissonClient));
    }

    @Bean
    @ConditionalOnMissingBean
    public io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiterRegistry rateLimiterRegistry(
            RedisStreamingProperties props,
            @org.springframework.beans.factory.annotation.Autowired(required = false) RedissonClient redissonClient) {
        var p = props.getRatelimit();
        java.util.Map<String, RateLimiter> map = new java.util.HashMap<>();
        if (p.getPolicies() == null || p.getPolicies().isEmpty()) {
            // Build single default from top-level properties
            var single = new RedisStreamingProperties.RateLimitProperties.Policy();
            single.setBackend(p.getBackend());
            single.setWindowMs(p.getWindowMs());
            single.setLimit(p.getLimit());
            single.setKeyPrefix(p.getKeyPrefix());
            map.put(p.getDefaultName(), buildOne(p.getDefaultName(), single, redissonClient));
        } else {
            p.getPolicies().forEach((name, policy) -> map.put(name, buildOne(name, policy, redissonClient)));
            // Ensure default exists
            map.putIfAbsent(p.getDefaultName(), buildOne(p.getDefaultName(),
                    p.getPolicies().getOrDefault(p.getDefaultName(), new RedisStreamingProperties.RateLimitProperties.Policy()), redissonClient));
        }
        return new io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiterRegistry(map);
    }

    @Bean
    @org.springframework.context.annotation.Primary
    @ConditionalOnMissingBean(RateLimiter.class)
    public RateLimiter rateLimiter(io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiterRegistry registry,
                                   RedisStreamingProperties props) {
        String name = props.getRatelimit().getDefaultName();
        RateLimiter rl = registry.get(name);
        if (rl == null) {
            // Fallback to any
            java.util.Map<String, RateLimiter> all = registry.all();
            rl = all.isEmpty() ? new InMemorySlidingWindowRateLimiter(1000, 100) : all.values().iterator().next();
            log.warn("RateLimiter defaultName='{}' not found; using {}", name, rl.getClass().getSimpleName());
        }
        return rl;
    }
}
