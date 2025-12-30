package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RedisBrokerFactory
 */
class RedisBrokerFactoryTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testCreateWithNullOptions() {
        BrokerFactory factory = new RedisBrokerFactory();
        Broker broker = factory.create(mockRedissonClient, null);

        assertNotNull(broker);
    }

    @Test
    void testCreateWithOptions() {
        BrokerFactory factory = new RedisBrokerFactory();
        MqOptions options = MqOptions.builder().build();
        Broker broker = factory.create(mockRedissonClient, options);

        assertNotNull(broker);
    }

    @Test
    void testCreateWithDefaultOptions() {
        BrokerFactory factory = new RedisBrokerFactory();
        MqOptions options = new MqOptions();
        Broker broker = factory.create(mockRedissonClient, options);

        assertNotNull(broker);
    }

    @Test
    void testCreateWithCustomOptions() {
        BrokerFactory factory = new RedisBrokerFactory();
        MqOptions options = MqOptions.builder()
                .keyPrefix("custom-prefix")
                .build();

        Broker broker = factory.create(mockRedissonClient, options);

        assertNotNull(broker);
    }

    @Test
    void testCreateReturnsNewInstanceEachTime() {
        BrokerFactory factory = new RedisBrokerFactory();
        MqOptions options = MqOptions.builder().build();

        Broker broker1 = factory.create(mockRedissonClient, options);
        Broker broker2 = factory.create(mockRedissonClient, options);

        assertNotNull(broker1);
        assertNotNull(broker2);
        // Each call should create a new instance
        assertNotSame(broker1, broker2);
    }

    @Test
    void testCreateWithEmptyRedissonClient() {
        BrokerFactory factory = new RedisBrokerFactory();

        // Should create broker even with mock client
        Broker broker = factory.create(mockRedissonClient, MqOptions.builder().build());

        assertNotNull(broker);
    }
}
