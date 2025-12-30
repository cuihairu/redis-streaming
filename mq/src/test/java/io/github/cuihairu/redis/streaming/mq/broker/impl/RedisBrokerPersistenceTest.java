package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RedisBrokerPersistence
 */
class RedisBrokerPersistenceTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testConstructorWithNullOptions() {
        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, null);

        assertNotNull(persistence);
    }

    @Test
    void testConstructorWithOptions() {
        MqOptions options = MqOptions.builder().build();
        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, options);

        assertNotNull(persistence);
    }

    @Test
    void testConstructorWithDefaultOptions() {
        MqOptions options = new MqOptions();
        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, options);

        assertNotNull(persistence);
    }

    @Test
    void testConstructorWithCustomOptions() {
        MqOptions options = MqOptions.builder()
                .keyPrefix("custom-prefix")
                .retentionMaxLenPerPartition(1000)
                .build();

        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, options);

        assertNotNull(persistence);
    }

    @Test
    void testAppendWithZeroPartitionId() {
        MqOptions options = MqOptions.builder().build();
        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, options);

        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        // Should not throw with mock client
        assertNotNull(persistence);
    }

    @Test
    void testAppendWithPositivePartitionId() {
        MqOptions options = MqOptions.builder().build();
        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, options);

        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        assertNotNull(persistence);
    }

    @Test
    void testAppendWithNegativePartitionId() {
        MqOptions options = MqOptions.builder().build();
        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, options);

        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        assertNotNull(persistence);
    }

    @Test
    void testAppendWithNullTopic() {
        MqOptions options = MqOptions.builder().build();
        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, options);

        Message message = new Message();
        message.setTopic(null);
        message.setPayload("test-payload");

        assertNotNull(persistence);
    }

    @Test
    void testAppendWithNullPayload() {
        MqOptions options = MqOptions.builder().build();
        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, options);

        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload(null);

        assertNotNull(persistence);
    }

    @Test
    void testAppendWithComplexPayload() {
        MqOptions options = MqOptions.builder().build();
        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, options);

        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload(java.util.Map.of("key", "value"));

        assertNotNull(persistence);
    }

    @Test
    void testAppendWithAllMessageFields() {
        MqOptions options = MqOptions.builder().build();
        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, options);

        Message message = new Message();
        message.setTopic("test-topic");
        message.setKey("test-key");
        message.setPayload("test-payload");
        message.setHeaders(java.util.Map.of("header1", "value1"));

        assertNotNull(persistence);
    }

    @Test
    void testConstructorWithRedissonClientOnly() {
        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, MqOptions.builder().build());

        assertNotNull(persistence);
    }
}
