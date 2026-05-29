package io.github.cuihairu.redis.streaming.mq.admin;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.util.Set;

/**
 * Topic Registry - maintains a registry of all active topics
 * <p>
 * Uses a Redis Set to track all active topics, avoiding the use of keys/scan commands
 * </p>
 */
@Slf4j
public class TopicRegistry {

    // Default keyspace prefix (can be overridden via constructor)
    public static final String DEFAULT_PREFIX = "streaming:mq";

    private final RedissonClient redissonClient;
    private final String keyPrefix;

    /**
     * Use default key prefix: streaming:mq
     */
    public TopicRegistry(RedissonClient redissonClient) {
        this(redissonClient, DEFAULT_PREFIX);
    }

    /**
     * Allow customizing the key prefix to avoid collisions in shared Redis.
     * Example final registry set key: {prefix}:topics:registry
     */
    public TopicRegistry(RedissonClient redissonClient, String keyPrefix) {
        this.redissonClient = redissonClient;
        this.keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? DEFAULT_PREFIX : keyPrefix;
    }

    private String registryKey() {
        return keyPrefix + ":topics:registry";
    }

    /**
     * Register a topic
     *
     * @param topic topic name
     * @return whether this is a newly registered topic
     */
    public boolean registerTopic(String topic) {
        try {
            RSet<String> registry = redissonClient.getSet(registryKey(), org.redisson.client.codec.StringCodec.INSTANCE);
            boolean added = registry.add(topic);

            if (added) {
                log.debug("Topic registered: {}", topic);
            }

            return added;
        } catch (Exception e) {
            log.error("Failed to register topic: {}", topic, e);
            return false;
        }
    }

    /**
     * Unregister a topic
     *
     * @param topic topic name
     * @return whether the topic was successfully removed
     */
    public boolean unregisterTopic(String topic) {
        try {
            RSet<String> registry = redissonClient.getSet(registryKey(), org.redisson.client.codec.StringCodec.INSTANCE);
            boolean removed = registry.remove(topic);

            if (removed) {
                log.info("Topic unregistered: {}", topic);
            }

            return removed;
        } catch (Exception e) {
            log.error("Failed to unregister topic: {}", topic, e);
            return false;
        }
    }

    /**
     * Check if a topic is registered
     *
     * @param topic topic name
     * @return whether the topic is registered
     */
    public boolean isTopicRegistered(String topic) {
        try {
            RSet<String> registry = redissonClient.getSet(registryKey(), org.redisson.client.codec.StringCodec.INSTANCE);
            return registry.contains(topic);
        } catch (Exception e) {
            log.error("Failed to check topic registration: {}", topic, e);
            return false;
        }
    }

    /**
     * Get all registered topics
     *
     * @return set of topic names
     */
    public Set<String> getAllTopics() {
        try {
            RSet<String> registry = redissonClient.getSet(registryKey(), org.redisson.client.codec.StringCodec.INSTANCE);
            return registry.readAll();
        } catch (Exception e) {
            log.error("Failed to get all topics", e);
            return Set.of();
        }
    }

    /**
     * Get the count of registered topics
     *
     * @return number of registered topics
     */
    public int getTopicCount() {
        try {
            RSet<String> registry = redissonClient.getSet(registryKey(), org.redisson.client.codec.StringCodec.INSTANCE);
            return registry.size();
        } catch (Exception e) {
            log.error("Failed to get topic count", e);
            return 0;
        }
    }

    /**
     * Clear all registered topics
     */
    public void clearRegistry() {
        try {
            RSet<String> registry = redissonClient.getSet(registryKey());
            registry.delete();
            log.info("Topic registry cleared");
        } catch (Exception e) {
            log.error("Failed to clear topic registry", e);
        }
    }
}
