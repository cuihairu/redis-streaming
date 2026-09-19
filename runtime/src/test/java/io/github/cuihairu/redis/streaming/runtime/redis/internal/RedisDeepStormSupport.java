package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/** Shared helper for runtime deep storms. */
final class RedisDeepStormSupport {
    private RedisDeepStormSupport() {}

    static RedissonClient client() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }
}
