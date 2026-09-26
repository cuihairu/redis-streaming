package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression test for B-31: {@code setHealthCheckTimeout(0)} used to flow straight
 * into HttpHealthChecker, whose HttpClient.connectTimeout(Duration.ZERO) threw
 * IllegalArgumentException — constructing the consumer blew up before start().
 * Non-positive values must normalize to the 5s default.
 */
class ConsumerZeroHealthCheckTimeoutStartTest {

    @Test
    void consumerWithZeroHealthCheckTimeoutConstructsAndStarts() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(true);
        config.setHealthCheckTimeout(0);

        RedisServiceConsumer consumer = new RedisServiceConsumer(mock(RedissonClient.class), config);
        consumer.start();
        try {
            assertTrue(consumer.isRunning(), "consumer must start with a normalized timeout");
        } finally {
            consumer.stop();
        }
        assertFalse(consumer.isRunning());
    }

    @Test
    void nonPositiveTimeoutsNormalizeToDefault() {
        ServiceConsumerConfig consumerConfig = new ServiceConsumerConfig();
        consumerConfig.setHealthCheckTimeout(0);
        assertEquals(5000, consumerConfig.getHealthCheckTimeout());
        consumerConfig.setHealthCheckTimeout(-1);
        assertEquals(5000, consumerConfig.getHealthCheckTimeout());
        consumerConfig.setHealthCheckTimeout(3000);
        assertEquals(3000, consumerConfig.getHealthCheckTimeout());

        NamingServiceConfig namingConfig = new NamingServiceConfig();
        namingConfig.setHealthCheckTimeout(0);
        assertEquals(5000, namingConfig.getHealthCheckTimeout());
        namingConfig.setHealthCheckTimeout(-1);
        assertEquals(5000, namingConfig.getHealthCheckTimeout());
        namingConfig.setHealthCheckTimeout(3000);
        assertEquals(3000, namingConfig.getHealthCheckTimeout());
    }
}
