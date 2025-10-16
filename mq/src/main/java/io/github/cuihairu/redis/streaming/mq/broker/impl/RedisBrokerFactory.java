package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerFactory;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerRouter;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.redisson.api.RedissonClient;

/** Default factory: Redis persistence + hash router. */
public class RedisBrokerFactory implements BrokerFactory {
    @Override
    public Broker create(RedissonClient redissonClient, MqOptions options) {
        BrokerRouter router = new HashBrokerRouter();
        BrokerPersistence persistence = new RedisBrokerPersistence(redissonClient, options);
        return new DefaultBroker(redissonClient, options, router, persistence);
    }
}
