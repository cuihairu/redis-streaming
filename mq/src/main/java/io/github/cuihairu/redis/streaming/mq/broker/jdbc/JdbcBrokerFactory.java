package io.github.cuihairu.redis.streaming.mq.broker.jdbc;

import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerFactory;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerRouter;
import io.github.cuihairu.redis.streaming.mq.broker.impl.DefaultBroker;
import io.github.cuihairu.redis.streaming.mq.broker.impl.HashBrokerRouter;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.redisson.api.RedissonClient;

import javax.sql.DataSource;

/** Broker factory backed by JDBC persistence (e.g., MySQL). */
public class JdbcBrokerFactory implements BrokerFactory {
    private final DataSource dataSource;

    public JdbcBrokerFactory(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Broker create(RedissonClient redissonClient, MqOptions options) {
        BrokerRouter router = new HashBrokerRouter();
        BrokerPersistence persistence = new JdbcBrokerPersistence(dataSource);
        return new DefaultBroker(redissonClient, options, router, persistence);
    }
}

