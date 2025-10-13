package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.impl.RedisMessageConsumer;
import io.github.cuihairu.redis.streaming.mq.impl.RedisMessageProducer;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

import java.util.UUID;

/**
 * Factory for creating message queue components
 */
@Slf4j
public class MessageQueueFactory {

    private final RedissonClient redissonClient;

    public MessageQueueFactory(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * Create a message producer
     *
     * @return message producer instance
     */
    public MessageProducer createProducer() {
        return new RedisMessageProducer(redissonClient);
    }

    /**
     * Create a message consumer with generated consumer name
     *
     * @return message consumer instance
     */
    public MessageConsumer createConsumer() {
        String consumerName = generateConsumerName();
        return new RedisMessageConsumer(redissonClient, consumerName);
    }

    /**
     * Create a message consumer with specified consumer name
     *
     * @param consumerName the consumer name
     * @return message consumer instance
     */
    public MessageConsumer createConsumer(String consumerName) {
        return new RedisMessageConsumer(redissonClient, consumerName);
    }

    /**
     * Create a dead letter queue consumer for processing failed messages
     *
     * @param originalTopic the original topic name
     * @return message consumer for dead letter queue
     */
    public MessageConsumer createDeadLetterConsumer(String originalTopic) {
        String dlqTopic = originalTopic + ".dlq";
        String consumerName = generateConsumerName() + "-dlq";
        return new RedisMessageConsumer(redissonClient, consumerName);
    }

    /**
     * Create a dead letter queue consumer with specified consumer name
     *
     * @param originalTopic the original topic name
     * @param consumerName the consumer name
     * @return message consumer for dead letter queue
     */
    public MessageConsumer createDeadLetterConsumer(String originalTopic, String consumerName) {
        return new RedisMessageConsumer(redissonClient, consumerName + "-dlq");
    }

    /**
     * Create a message queue admin for monitoring and management
     *
     * @return message queue admin instance
     */
    public MessageQueueAdmin createAdmin() {
        return new RedisMessageQueueAdmin(redissonClient);
    }

    private String generateConsumerName() {
        return "consumer-" + UUID.randomUUID().toString().substring(0, 8);
    }
}