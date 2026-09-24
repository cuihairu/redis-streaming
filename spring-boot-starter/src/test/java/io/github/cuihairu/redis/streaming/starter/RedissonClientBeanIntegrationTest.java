package io.github.cuihairu.redis.streaming.starter;

import io.github.cuihairu.redis.streaming.starter.autoconfigure.RedisStreamingAutoConfiguration;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-Redis coverage for the redissonClient bean factory method (single-server branch). */
@Tag("integration")
class RedissonClientBeanIntegrationTest {

    @Test
    void redissonClientBeanConnectsToSingleServer() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRedis().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        props.getRedis().setPassword(null);

        RedissonClient client = new RedisStreamingAutoConfiguration().redissonClient(props);
        try {
            assertNotNull(client);
            assertTrue(client.getKeys().count() >= 0);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void redissonClientBeanHonoursPasswordBranch() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRedis().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        // local test Redis has no password; supplying a blank password must take the same path as null
        props.getRedis().setPassword("  ");
        RedissonClient client = new RedisStreamingAutoConfiguration().redissonClient(props);
        try {
            assertNotNull(client);
        } finally {
            client.shutdown();
        }
    }
}
