package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.impl.RedisMessageConsumer;
import io.github.cuihairu.redis.streaming.mq.impl.RedisMessageProducer;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.HashPartitioner;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

import java.util.UUID;

/**
 * Factory for creating message queue components
 */
@Slf4j
public class MessageQueueFactory {

    private final RedissonClient redissonClient;
    private final MqOptions options;
    private final io.github.cuihairu.redis.streaming.mq.broker.BrokerFactory brokerFactory;

    public MessageQueueFactory(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.options = MqOptions.builder().build();
        this.brokerFactory = new io.github.cuihairu.redis.streaming.mq.broker.impl.RedisBrokerFactory();
        // Configure key prefixes once per factory instance
        io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.configure(
                this.options.getKeyPrefix(), this.options.getStreamKeyPrefix());
    }

    public MessageQueueFactory(RedissonClient redissonClient, MqOptions options) {
        this.redissonClient = redissonClient;
        this.options = options == null ? MqOptions.builder().build() : options;
        this.brokerFactory = new io.github.cuihairu.redis.streaming.mq.broker.impl.RedisBrokerFactory();
        io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.configure(
                this.options.getKeyPrefix(), this.options.getStreamKeyPrefix());
    }

    public MessageQueueFactory(RedissonClient redissonClient, MqOptions options,
                               io.github.cuihairu.redis.streaming.mq.broker.BrokerFactory brokerFactory) {
        this.redissonClient = redissonClient;
        this.options = options == null ? MqOptions.builder().build() : options;
        this.brokerFactory = brokerFactory == null ? new io.github.cuihairu.redis.streaming.mq.broker.impl.RedisBrokerFactory() : brokerFactory;
        io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.configure(
                this.options.getKeyPrefix(), this.options.getStreamKeyPrefix());
    }

    /**
     * Create a message producer
     *
     * @return message producer instance
     */
    public MessageProducer createProducer() {
        // Delegate via BrokerFactory to allow swapping persistence (redis|jdbc)
        io.github.cuihairu.redis.streaming.mq.broker.Broker broker = brokerFactory.create(redissonClient, options);
        return new io.github.cuihairu.redis.streaming.mq.impl.BrokerBackedProducer(broker);
    }

    /**
     * Create a message consumer with generated consumer name
     *
     * @return message consumer instance
     */
    public MessageConsumer createConsumer() {
        String consumerName = generateConsumerName();
        io.github.cuihairu.redis.streaming.mq.broker.Broker broker = brokerFactory.create(redissonClient, options);
        return new RedisMessageConsumer(redissonClient, consumerName,
                new TopicPartitionRegistry(redissonClient), options, broker);
    }

    /**
     * Create a message consumer with specified consumer name
     *
     * @param consumerName the consumer name
     * @return message consumer instance
     */
    public MessageConsumer createConsumer(String consumerName) {
        io.github.cuihairu.redis.streaming.mq.broker.Broker broker = brokerFactory.create(redissonClient, options);
        return new RedisMessageConsumer(redissonClient, consumerName,
                new TopicPartitionRegistry(redissonClient), options, broker);
    }

    /**
     * Create a dead letter queue consumer. Topic binding happens on subscribe().
     */
    public MessageConsumer createDeadLetterConsumer() {
        String consumerName = generateConsumerName() + options.getDlqConsumerSuffix();
        return new io.github.cuihairu.redis.streaming.mq.impl.DlqConsumerAdapter(redissonClient, consumerName, options);
    }

    /**
     * Create a dead letter queue consumer with specified consumer name.
     */
    public MessageConsumer createDeadLetterConsumer(String consumerName) {
        String name = (consumerName == null || consumerName.isBlank())
                ? generateConsumerName() + options.getDlqConsumerSuffix()
                : (consumerName.endsWith(options.getDlqConsumerSuffix()) ? consumerName : consumerName + options.getDlqConsumerSuffix());
        return new io.github.cuihairu.redis.streaming.mq.impl.DlqConsumerAdapter(redissonClient, name, options);
    }

    // Removed legacy overloads that took originalTopic; users should call subscribe(topic, ...) on the returned consumer.

    /**
     * Create a message queue admin for monitoring and management
     *
     * @return message queue admin instance
     */
    public MessageQueueAdmin createAdmin() {
        return new RedisMessageQueueAdmin(redissonClient, options);
    }

    /**
     * Convenience: create a DLQ consumer bound to a topic and handler, and start it.
     * This method has side-effects (starts the consumer).
     */
    public MessageConsumer createDeadLetterConsumerForTopic(String topic,
                                                           String group,
                                                           String consumerName,
                                                           MessageHandler handler) {
        String name = (consumerName == null || consumerName.isBlank())
                ? generateConsumerName() + options.getDlqConsumerSuffix()
                : (consumerName.endsWith(options.getDlqConsumerSuffix()) ? consumerName : consumerName + options.getDlqConsumerSuffix());
        String g = (group == null || group.isBlank()) ? options.getDefaultDlqGroup() : group;
        MessageConsumer c = new io.github.cuihairu.redis.streaming.mq.impl.DlqConsumerAdapter(redissonClient, name, options);
        c.subscribe(topic, g, handler);
        c.start();
        return c;
    }

    private String generateConsumerName() {
        return options.getConsumerNamePrefix() + UUID.randomUUID().toString().substring(0, 8);
    }
}
