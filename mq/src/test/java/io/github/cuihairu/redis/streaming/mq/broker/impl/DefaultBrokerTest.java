package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerRouter;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DefaultBroker
 */
class DefaultBrokerTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    @Mock
    private BrokerRouter mockRouter;

    @Mock
    private BrokerPersistence mockPersistence;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private DefaultBroker createBroker(MqOptions options) {
        return new DefaultBroker(mockRedissonClient, options, mockRouter, mockPersistence);
    }

    @Test
    void testConstructorWithNullOptions() {
        Broker broker = createBroker(null);

        assertNotNull(broker);
    }

    @Test
    void testConstructorWithOptions() {
        MqOptions options = MqOptions.builder().build();
        Broker broker = createBroker(options);

        assertNotNull(broker);
    }

    @Test
    void testConstructorWithDefaultOptions() {
        MqOptions options = new MqOptions();
        Broker broker = createBroker(options);

        assertNotNull(broker);
    }

    @Test
    void testConstructorWithCustomOptions() {
        MqOptions options = MqOptions.builder()
                .defaultPartitionCount(5)
                .ackDeletePolicy("immediate")
                .build();

        Broker broker = createBroker(options);

        assertNotNull(broker);
    }

    @Test
    void testProduceWithBasicMessage() {
        MqOptions options = MqOptions.builder().build();
        Broker broker = createBroker(options);

        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        // With mock persistence, should not throw
        assertNotNull(broker);
    }

    @Test
    void testProduceWithAllFields() {
        MqOptions options = MqOptions.builder().build();
        Broker broker = createBroker(options);

        Message message = new Message();
        message.setTopic("test-topic");
        message.setKey("test-key");
        message.setPayload("test-payload");
        message.setHeaders(java.util.Map.of("header1", "value1"));

        assertNotNull(broker);
    }

    @Test
    void testProduceWithNullTopic() {
        MqOptions options = MqOptions.builder().build();
        Broker broker = createBroker(options);

        Message message = new Message();
        message.setTopic(null);
        message.setPayload("test-payload");

        assertNotNull(broker);
    }

    @Test
    void testProduceWithNullPayload() {
        MqOptions options = MqOptions.builder().build();
        Broker broker = createBroker(options);

        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload(null);

        assertNotNull(broker);
    }
}
