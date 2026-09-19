package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.broker.impl.DefaultBroker;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Error-path storms for consumer/broker implementations: armed failing Redisson clients make
 * every Redis call throw, exercising the internal catch/fallback plumbing.
 */
class MqErrorPathStormTest {

    private static Map<Class<?>, Object> hints() {
        Map<Class<?>, Object> hints = new HashMap<>();
        hints.put(MessageHandler.class, (MessageHandler) m -> MessageHandleResult.SUCCESS);
        return hints;
    }

    @Test
    void consumerStormHappyAndFailing() {
        RedissonClient deep = Storms.deep(RedissonClient.class);
        TopicPartitionRegistry registry = Storms.constructing(() -> new TopicPartitionRegistry(deep));
        MqOptions options = MqOptions.builder().claimIdleMs(1).pendingScanIntervalSec(1).build();
        RedisMessageConsumer happy = Storms.constructing(
                () -> new RedisMessageConsumer(deep, "c-storm", registry, options, null));
        try {
            assertTrue(Storms.storm(happy, hints()) > 8);
        } finally {
            Storms.storm(happy, null);
        }

        RedissonClient boom = Storms.exploding(RedissonClient.class);
        TopicPartitionRegistry failingRegistry = Storms.constructing(() -> new TopicPartitionRegistry(boom));
        RedisMessageConsumer failing = Storms.constructing(
                () -> new RedisMessageConsumer(boom, "c-storm-2", failingRegistry, options, null));
        assertTrue(Storms.storm(failing, hints()) > 8);

    }

    @Test
    void defaultBrokerStorm() {
        AtomicInteger seq = new AtomicInteger();
        DefaultBroker failing = Storms.constructing(() -> new DefaultBroker(
                Storms.exploding(RedissonClient.class), MqOptions.builder().build(),
                (t, k, h, pc) -> seq.get() % Math.max(1, pc), (t, p, m) -> "k" + m));
        assertTrue(Storms.storm(failing, null) >= 2);
        DefaultBroker happy = Storms.constructing(() -> new DefaultBroker(
                Storms.deep(RedissonClient.class), MqOptions.builder().build(),
                (t, k, h, pc) -> 0, (t, p, m) -> "k"));
        assertTrue(Storms.storm(happy, null) >= 2);
    }

    @Test
    void dlqAdapterStorm() {
        RedissonClient boom = Storms.exploding(RedissonClient.class);
        DlqConsumerAdapter failing = Storms.constructing(
                () -> new DlqConsumerAdapter(boom, "c-dlq", MqOptions.builder().build()));
        assertTrue(Storms.storm(failing, hints()) > 5);
        DlqConsumerAdapter happy = Storms.constructing(
                () -> new DlqConsumerAdapter(Storms.deep(RedissonClient.class), "c-dlq", MqOptions.builder().build()));
        assertTrue(Storms.storm(happy, hints()) > 5);
    }
}
