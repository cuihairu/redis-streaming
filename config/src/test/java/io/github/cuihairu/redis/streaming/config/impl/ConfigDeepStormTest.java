package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import static org.junit.jupiter.api.Assertions.*;

/** Deep storm for RedisConfigService internals against real Redis (Lua fallbacks included). */
@Tag("integration")
class ConfigDeepStormTest {

    @Test
    void configServiceDeepStorm() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        RedissonClient redis = Redisson.create(cfg);
        RedisConfigService service = new RedisConfigService(redis, new ConfigServiceConfig("deep-cfg-" + System.nanoTime(), true));
        try {
            assertTrue(Storms.stormDeep(service, java.util.Map.of(), 400) > 10);
        } finally {
            service.stop();
            redis.shutdown();
        }
    }
}
