package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every mq class with a timeout-guarded invocation pass. */
class MqGrandStormTest {

    @Test
    void sweepAllClasses() throws Exception {
        java.util.Map<Class<?>, Object> hints = new java.util.HashMap<>();
        org.redisson.config.Config redisCfg = new org.redisson.config.Config();
        redisCfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        org.redisson.api.RedissonClient real = org.redisson.Redisson.create(redisCfg);
        hints.put(org.redisson.api.RedissonClient.class, real);
        hints.put(MessageHandler.class, (MessageHandler) m -> MessageHandleResult.SUCCESS);
        hints.put(Message.class, new Message("t", "k", "p"));
        int total = Storms.grandStorm(MessageQueueFactory.class, "io.github.cuihairu.redis.streaming.mq", hints, 150);
        System.err.println("GRAND-REDIS mq invocations=" + total);
        real.shutdown();
    }
}
