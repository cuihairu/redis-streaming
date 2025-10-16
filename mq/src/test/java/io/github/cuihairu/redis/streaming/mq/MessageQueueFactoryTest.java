package io.github.cuihairu.redis.streaming.mq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessageQueueFactoryTest {

    @Mock
    private RedissonClient redissonClient;

    private MessageQueueFactory factory;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        factory = new MessageQueueFactory(redissonClient);
    }

    @Test
    void testCreateProducer() {
        MessageProducer producer = factory.createProducer();

        assertNotNull(producer);
        // Producer delegates to Broker now (router + persistence)
        assertTrue(producer instanceof io.github.cuihairu.redis.streaming.mq.impl.BrokerBackedProducer);
    }

    @Test
    void testCreateConsumer() {
        MessageConsumer consumer = factory.createConsumer();

        assertNotNull(consumer);
        assertTrue(consumer instanceof io.github.cuihairu.redis.streaming.mq.impl.RedisMessageConsumer);
    }

    @Test
    void testCreateConsumerWithName() {
        String consumerName = "custom-consumer";
        MessageConsumer consumer = factory.createConsumer(consumerName);

        assertNotNull(consumer);
        assertTrue(consumer instanceof io.github.cuihairu.redis.streaming.mq.impl.RedisMessageConsumer);
    }

    @Test
    void testCreateDeadLetterConsumer() {
        MessageConsumer dlqConsumer = factory.createDeadLetterConsumer();

        assertNotNull(dlqConsumer);
        // DLQ consumer is provided via reliability adapter
        assertTrue(dlqConsumer instanceof io.github.cuihairu.redis.streaming.mq.impl.DlqConsumerAdapter);
    }

    @Test
    void testCreateDeadLetterConsumerWithName() {
        String consumerName = "custom-consumer";
        MessageConsumer dlqConsumer = factory.createDeadLetterConsumer(consumerName);

        assertNotNull(dlqConsumer);
        assertTrue(dlqConsumer instanceof io.github.cuihairu.redis.streaming.mq.impl.DlqConsumerAdapter);
    }
}
