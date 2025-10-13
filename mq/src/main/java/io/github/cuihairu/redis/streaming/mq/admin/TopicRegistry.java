package io.github.cuihairu.redis.streaming.mq.admin;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.util.Set;

/**
 * Topic Registry - 维护所有活跃 topic 的注册表
 * <p>
 * 使用 Redis Set 来追踪所有活跃的 topic，避免使用 keys/scan 命令
 * </p>
 */
@Slf4j
public class TopicRegistry {

    private static final String REGISTRY_KEY = "streaming:mq:topics:registry";

    private final RedissonClient redissonClient;

    public TopicRegistry(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 注册一个 topic
     *
     * @param topic topic 名称
     * @return 是否是新注册的 topic
     */
    public boolean registerTopic(String topic) {
        try {
            RSet<String> registry = redissonClient.getSet(REGISTRY_KEY);
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
     * 取消注册一个 topic
     *
     * @param topic topic 名称
     * @return 是否成功移除
     */
    public boolean unregisterTopic(String topic) {
        try {
            RSet<String> registry = redissonClient.getSet(REGISTRY_KEY);
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
     * 检查 topic 是否已注册
     *
     * @param topic topic 名称
     * @return 是否已注册
     */
    public boolean isTopicRegistered(String topic) {
        try {
            RSet<String> registry = redissonClient.getSet(REGISTRY_KEY);
            return registry.contains(topic);
        } catch (Exception e) {
            log.error("Failed to check topic registration: {}", topic, e);
            return false;
        }
    }

    /**
     * 获取所有已注册的 topics
     *
     * @return topic 集合
     */
    public Set<String> getAllTopics() {
        try {
            RSet<String> registry = redissonClient.getSet(REGISTRY_KEY);
            return registry.readAll();
        } catch (Exception e) {
            log.error("Failed to get all topics", e);
            return Set.of();
        }
    }

    /**
     * 获取已注册的 topic 数量
     *
     * @return topic 数量
     */
    public int getTopicCount() {
        try {
            RSet<String> registry = redissonClient.getSet(REGISTRY_KEY);
            return registry.size();
        } catch (Exception e) {
            log.error("Failed to get topic count", e);
            return 0;
        }
    }

    /**
     * 清空所有注册的 topics
     */
    public void clearRegistry() {
        try {
            RSet<String> registry = redissonClient.getSet(REGISTRY_KEY);
            registry.delete();
            log.info("Topic registry cleared");
        } catch (Exception e) {
            log.error("Failed to clear topic registry", e);
        }
    }
}
