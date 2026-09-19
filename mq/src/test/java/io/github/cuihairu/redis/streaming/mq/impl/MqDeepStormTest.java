package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
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

import static org.junit.jupiter.api.Assertions.*;

/** Deep (private-method, timeout-guarded) storms for the consumer/broker internals. */
class MqDeepStormTest {

    private static Map<Class<?>, Object> hints() {
        Map<Class<?>, Object> h = new HashMap<>();
        h.put(MessageHandler.class, (MessageHandler) m -> MessageHandleResult.SUCCESS);
        h.put(Message.class, new Message("t", "k", "p"));
        return h;
    }

    @Test
    void consumerDeepStorm() {
        RedissonClient deep = Storms.deep(RedissonClient.class);
        MqOptions options = MqOptions.builder().workerThreads(1).schedulerThreads(1)
                .claimIdleMs(1).pendingScanIntervalSec(1).rebalanceIntervalSec(1).renewIntervalSec(1).build();
        RedisMessageConsumer consumer = Storms.constructing(() -> new RedisMessageConsumer(
                deep, "c-deep", Storms.constructing(() -> new TopicPartitionRegistry(deep)), options, null));
        try {
            assertTrue(Storms.stormDeep(consumer, hints(), 300, "start", "close", "stop") > 10);
        } finally {
            consumer.stop();
            consumer.close();
        }
        RedissonClient boom = Storms.exploding(RedissonClient.class);
        RedisMessageConsumer failing = Storms.constructing(() -> new RedisMessageConsumer(
                boom, "c-deep-f", Storms.constructing(() -> new TopicPartitionRegistry(boom)), options, null));
        try {
            assertTrue(Storms.stormDeep(failing, hints(), 300, "start") > 10);
        } finally {
            failing.stop();
        }
    }

    @Test
    void brokerDeepStorm() {
        DefaultBroker failing = Storms.constructing(() -> new DefaultBroker(
                Storms.exploding(RedissonClient.class), MqOptions.builder().build(),
                (t, k, h, pc) -> 0, (t, p, m) -> "k"));
        assertTrue(Storms.stormDeep(failing, hints(), 300) > 3);
        DefaultBroker happy = Storms.constructing(() -> new DefaultBroker(
                Storms.deep(RedissonClient.class), MqOptions.builder().build(),
                (t, k, h, pc) -> 0, (t, p, m) -> "k"));
        assertTrue(Storms.stormDeep(happy, hints(), 300) > 3);
    }
}
