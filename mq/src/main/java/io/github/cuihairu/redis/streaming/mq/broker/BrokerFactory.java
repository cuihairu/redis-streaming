package io.github.cuihairu.redis.streaming.mq.broker;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.redisson.api.RedissonClient;

/** Factory to create Broker instances for producers/consumers. */
public interface BrokerFactory {
    Broker create(RedissonClient redissonClient, MqOptions options);
}

