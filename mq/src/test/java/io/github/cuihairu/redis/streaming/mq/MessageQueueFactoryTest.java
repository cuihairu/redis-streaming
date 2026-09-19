package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MessageQueueFactoryTest {

    @Test
    void nullOptionsAndFactoryFallBackToDefaults() {
        RedissonClient client = mock(RedissonClient.class);
        MessageQueueFactory f = new MessageQueueFactory(client, null);
        assertNotNull(f.createProducer());
        assertNotNull(f.createConsumer());
        assertNotNull(f.createConsumer("named"));
        assertNotNull(f.createAdmin());
    }

    @Test
    void deadLetterConsumerNaming() {
        RedissonClient client = mock(RedissonClient.class);
        MqOptions options = MqOptions.builder().consumerNamePrefix("c-").dlqConsumerSuffix("-dlq").build();
        MessageQueueFactory f = new MessageQueueFactory(client, options, null);

        assertNotNull(f.createDeadLetterConsumer());

        MessageConsumer keepsSuffix = f.createDeadLetterConsumer("worker-dlq");
        assertNotNull(keepsSuffix);
        MessageConsumer appendsSuffix = f.createDeadLetterConsumer("worker");
        assertNotNull(appendsSuffix);
        MessageConsumer blankName = f.createDeadLetterConsumer("   ");
        assertNotNull(blankName);
    }

    @Test
    void deadLetterConsumerForTopicStartsAndDefaultsGroup() {
        RedissonClient client = mock(RedissonClient.class);
        MqOptions options = MqOptions.builder().defaultDlqGroup("fallback-group").build();
        MessageQueueFactory f = new MessageQueueFactory(client, options, null);
        assertNotNull(f.createDeadLetterConsumerForTopic("t", null, null, m -> MessageHandleResult.SUCCESS));
        assertNotNull(f.createDeadLetterConsumerForTopic("t", "explicit", "named", m -> MessageHandleResult.SUCCESS));
    }
}
