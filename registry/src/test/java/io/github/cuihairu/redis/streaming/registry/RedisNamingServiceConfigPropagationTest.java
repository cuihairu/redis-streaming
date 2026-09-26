package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression test for B-30: RedisNamingService built its role-specific configs by
 * copying only keyPrefix/enableKeyPrefix — every health-check and admin setting a user
 * put on NamingServiceConfig was silently dropped, while getConfig() kept returning the
 * user's values, making the misconfiguration invisible.
 */
class RedisNamingServiceConfigPropagationTest {

    private static ServiceConsumerConfig consumerConfigOf(RedisNamingService namingService) throws Exception {
        Field f = RedisNamingService.class.getDeclaredField("serviceConsumer");
        f.setAccessible(true);
        RedisServiceConsumer consumer = (RedisServiceConsumer) f.get(namingService);
        Field cfg = RedisServiceConsumer.class.getDeclaredField("config");
        cfg.setAccessible(true);
        return (ServiceConsumerConfig) cfg.get(consumer);
    }

    @Test
    void healthCheckAndAdminSettingsPropagateToConsumerConfig() throws Exception {
        NamingServiceConfig config = new NamingServiceConfig();
        config.setEnableHealthCheck(true);
        config.setHealthCheckInterval(7);
        config.setHealthCheckTimeUnit(TimeUnit.MILLISECONDS);
        config.setHealthCheckTimeout(1234);
        config.setEnableAdminService(false);

        RedisNamingService namingService = new RedisNamingService(mock(RedissonClient.class), config);
        ServiceConsumerConfig consumerConfig = consumerConfigOf(namingService);

        assertTrue(consumerConfig.isEnableHealthCheck(),
                "enableHealthCheck must reach the consumer config (old code: silently dropped)");
        assertEquals(7L, consumerConfig.getHealthCheckInterval());
        assertEquals(TimeUnit.MILLISECONDS, consumerConfig.getHealthCheckTimeUnit());
        assertEquals(1234, consumerConfig.getHealthCheckTimeout());
        assertFalse(consumerConfig.isEnableAdminService());
    }

    @Test
    void defaultsPropagateWhenNothingIsCustomized() throws Exception {
        RedisNamingService namingService = new RedisNamingService(mock(RedissonClient.class));
        ServiceConsumerConfig consumerConfig = consumerConfigOf(namingService);

        assertFalse(consumerConfig.isEnableHealthCheck());
        assertEquals(30L, consumerConfig.getHealthCheckInterval());
        assertEquals(TimeUnit.SECONDS, consumerConfig.getHealthCheckTimeUnit());
        assertEquals(5000, consumerConfig.getHealthCheckTimeout());
        assertTrue(consumerConfig.isEnableAdminService());
    }

    @Test
    void keyPrefixStillPropagatesToBothRoles() throws Exception {
        NamingServiceConfig config = new NamingServiceConfig("custom-prefix");
        RedisNamingService namingService = new RedisNamingService(mock(RedissonClient.class), config);

        Field p = RedisNamingService.class.getDeclaredField("serviceProvider");
        p.setAccessible(true);
        Object provider = p.get(namingService);
        Field pCfg = provider.getClass().getDeclaredField("config");
        pCfg.setAccessible(true);
        ServiceProviderConfig providerConfig = (ServiceProviderConfig) pCfg.get(provider);

        assertEquals("custom-prefix", providerConfig.getKeyPrefix());
        assertEquals("custom-prefix", consumerConfigOf(namingService).getKeyPrefix());
    }
}
