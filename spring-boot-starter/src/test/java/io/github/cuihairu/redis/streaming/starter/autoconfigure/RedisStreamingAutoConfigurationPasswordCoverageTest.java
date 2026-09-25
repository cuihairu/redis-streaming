package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Covers the password branch of {@code RedisStreamingAutoConfiguration#redissonClient(...)}:
 * configured credentials must reach the Redisson single-server config, blank ones must not.
 */
class RedisStreamingAutoConfigurationPasswordCoverageTest {

    private static Config buildClientConfig(RedisStreamingProperties props) {
        try (MockedStatic<Redisson> redisson = mockStatic(Redisson.class)) {
            RedissonClient client = mock(RedissonClient.class);
            ArgumentCaptor<Config> captor = ArgumentCaptor.forClass(Config.class);
            redisson.when(() -> Redisson.create(any(Config.class))).thenReturn(client);

            RedissonClient out = new RedisStreamingAutoConfiguration().redissonClient(props);
            assertSame(client, out);

            redisson.verify(() -> Redisson.create(captor.capture()));
            return captor.getValue();
        }
    }

    @Test
    void configuredPasswordIsForwardedToRedisson() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRedis().setAddress("redis://127.0.0.1:6379");
        props.getRedis().setPassword("s3cr3t");

        Config config = buildClientConfig(props);
        assertEquals("s3cr3t", config.useSingleServer().getPassword());
    }

    @Test
    void blankPasswordLeavesRedissonUnauthenticated() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRedis().setPassword("   ");
        assertNull(buildClientConfig(props).useSingleServer().getPassword());

        RedisStreamingProperties nulled = new RedisStreamingProperties();
        nulled.getRedis().setPassword(null);
        assertNull(buildClientConfig(nulled).useSingleServer().getPassword());
    }
}
