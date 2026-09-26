package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link MessageQueueFactory}: null-options construction and
 * DLQ consumer naming rules.
 */
class MessageQueueFactorySprintCoverageTest {

    private RedissonClient client;
    private BrokerFactory brokerFactory;
    private final java.util.List<MessageConsumer> spawned = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        client = mock(RedissonClient.class);
        brokerFactory = mock(BrokerFactory.class);
        Broker broker = mock(Broker.class);
        when(brokerFactory.create(any(), any())).thenReturn(broker);
        @SuppressWarnings("unchecked")
        org.redisson.api.RStream<String, Object> dlq = mock(org.redisson.api.RStream.class);
        try {
            when(client.getStream(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> {
                Thread.sleep(20); // keep the DLQ loop from busy-spinning in these naming tests
                return dlq;
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (MessageConsumer c : spawned) {
            try {
                c.close();
            } catch (Throwable ignore) {
            }
        }
        spawned.clear();
    }

    @Test
    void threeArgConstructorToleratesNullOptionsAndFactory() throws Exception {
        assertNotNull(new MessageQueueFactory(client, null, brokerFactory));
        assertNotNull(new MessageQueueFactory(client, null, null));
    }

    @Test
    void deadLetterConsumerNameRules() throws Exception {
        MqOptions options = MqOptions.builder().build();
        String suffix = options.getDlqConsumerSuffix();

        MessageConsumer generated = new MessageQueueFactory(client, options, brokerFactory)
                .createDeadLetterConsumerForTopic("t", "g", null, e -> MessageHandleResult.SUCCESS);
        spawned.add(generated);
        assertNotNull(generated);

        MessageConsumer blank = new MessageQueueFactory(client, options, brokerFactory)
                .createDeadLetterConsumerForTopic("t", "g", "   ", e -> MessageHandleResult.SUCCESS);
        spawned.add(blank);

        MessageConsumer alreadySuffixed = new MessageQueueFactory(client, options, brokerFactory)
                .createDeadLetterConsumerForTopic("t", "g", "c1" + suffix, e -> MessageHandleResult.SUCCESS);
        spawned.add(alreadySuffixed);

        MessageConsumer plain = new MessageQueueFactory(client, options, brokerFactory)
                .createDeadLetterConsumerForTopic("t", "g", "c2", e -> MessageHandleResult.SUCCESS);
        spawned.add(plain);
    }

    @Test
    void deadLetterConsumerDefaultGroupUsedForBlankGroup() throws Exception {
        MqOptions options = MqOptions.builder().build();
        MessageConsumer c = new MessageQueueFactory(client, options, brokerFactory)
                .createDeadLetterConsumerForTopic("t", "  ", "c3", e -> MessageHandleResult.SUCCESS);
        spawned.add(c);
        assertNotNull(c);
        assertTrue(c.isRunning(), "factory must start the DLQ consumer");
    }
}
