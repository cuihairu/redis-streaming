package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.broker.BrokerFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

/**
 * Covers MessageQueueFactory constructor variants and DLQ consumer naming branches.
 */
class MessageQueueFactoryNamingCoverageTest {

    @Test
    void threeArgConstructorKeepsProvidedBrokerFactory() {
        RedissonClient client = mock(RedissonClient.class);
        BrokerFactory factory = mock(BrokerFactory.class);
        MessageQueueFactory mq = new MessageQueueFactory(client, MqOptions.builder().build(), factory);
        assertNotNull(mq.createProducer());
        assertNotNull(mq.createAdmin());
    }

    @Test
    void createDeadLetterConsumerForTopicKeepsNameWithSuffixAndBlankGroup() {
        RedissonClient client = mock(RedissonClient.class);
        MessageQueueFactory mq = new MessageQueueFactory(client,
                MqOptions.builder().dlqConsumerSuffix("-dlq").build(), null);
        MessageConsumer c = mq.createDeadLetterConsumerForTopic("t", "  ", "named-dlq",
                m -> MessageHandleResult.SUCCESS);
        try {
            assertNotNull(c);
            assertTrue(c.isRunning());
        } finally {
            c.close();
        }

        MessageConsumer c2 = mq.createDeadLetterConsumerForTopic("t", "g", "named-dlq",
                m -> MessageHandleResult.SUCCESS);
        try {
            assertNotNull(c2);
        } finally {
            c2.close();
        }
    }

    @Test
    void createDeadLetterConsumerPreservesSuffixedName() {
        RedissonClient client = mock(RedissonClient.class);
        MessageQueueFactory mq = new MessageQueueFactory(client,
                MqOptions.builder().dlqConsumerSuffix("-dlq").build(), null);
        MessageConsumer c = mq.createDeadLetterConsumer("custom-dlq");
        try {
            assertNotNull(c);
        } finally {
            c.close();
        }
    }
}
