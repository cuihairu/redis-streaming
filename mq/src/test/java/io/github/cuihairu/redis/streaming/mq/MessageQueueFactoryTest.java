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
        assertTrue(producer instanceof io.github.cuihairu.redis.streaming.mq.impl.RedisMessageProducer);
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
        String originalTopic = "test-topic";
        MessageConsumer dlqConsumer = factory.createDeadLetterConsumer(originalTopic);

        assertNotNull(dlqConsumer);
        // DLQ consumer is a dedicated implementation
        assertTrue(dlqConsumer instanceof io.github.cuihairu.redis.streaming.mq.impl.RedisDeadLetterConsumer);
    }

    @Test
    void testCreateDeadLetterConsumerWithName() {
        String originalTopic = "test-topic";
        String consumerName = "custom-consumer";
        MessageConsumer dlqConsumer = factory.createDeadLetterConsumer(originalTopic, consumerName);

        assertNotNull(dlqConsumer);
        assertTrue(dlqConsumer instanceof io.github.cuihairu.redis.streaming.mq.impl.RedisDeadLetterConsumer);
    }
}
