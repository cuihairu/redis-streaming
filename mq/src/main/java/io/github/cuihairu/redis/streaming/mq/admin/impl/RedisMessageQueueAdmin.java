package io.github.cuihairu.redis.streaming.mq.admin.impl;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.TopicRegistry;
import io.github.cuihairu.redis.streaming.mq.admin.model.*;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.redisson.api.stream.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis Streams 消息队列管理实现
 */
@Slf4j
public class RedisMessageQueueAdmin implements MessageQueueAdmin {

    private final RedissonClient redissonClient;
    private final TopicRegistry topicRegistry;

    public RedisMessageQueueAdmin(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.topicRegistry = new TopicRegistry(redissonClient);
    }

    // ==================== 队列信息查询 ====================

    @Override
    public QueueInfo getQueueInfo(String topic) {
        try {
            RStream<String, Object> stream = redissonClient.getStream(topic);

            // 检查队列是否存在
            if (!stream.isExists()) {
                return QueueInfo.builder()
                        .topic(topic)
                        .exists(false)
                        .length(0)
                        .consumerGroupCount(0)
                        .build();
            }

            // 获取队列长度
            long length = stream.size();

            // 获取消费者组数量
            StreamInfo<String, Object> info = stream.getInfo();
            int groupCount = info.getGroups();

            return QueueInfo.builder()
                    .topic(topic)
                    .exists(true)
                    .length(length)
                    .consumerGroupCount(groupCount)
                    .firstMessageId(info.getFirstEntry() != null ? info.getFirstEntry().getId() : null)
                    .lastMessageId(info.getLastGeneratedId())
                    .createdAt(null) // Redis Streams 不提供创建时间
                    .lastUpdatedAt(info.getLastGeneratedId() != null ?
                            Instant.ofEpochMilli(Long.parseLong(info.getLastGeneratedId().toString().split("-")[0])) : null)
                    .build();

        } catch (Exception e) {
            log.error("Failed to get queue info for topic: {}", topic, e);
            return QueueInfo.builder()
                    .topic(topic)
                    .exists(false)
                    .length(0)
                    .consumerGroupCount(0)
                    .build();
        }
    }

    @Override
    public List<String> listAllTopics() {
        try {
            // 使用 TopicRegistry 获取所有已注册的 topics
            // 避免使用 keys/scan 命令，提高生产环境性能
            Set<String> topics = topicRegistry.getAllTopics();
            return new ArrayList<>(topics);

        } catch (Exception e) {
            log.error("Failed to list all topics", e);
            return Collections.emptyList();
        }
    }

    @Override
    public boolean topicExists(String topic) {
        try {
            RStream<String, Object> stream = redissonClient.getStream(topic);
            return stream.isExists();
        } catch (Exception e) {
            log.error("Failed to check if topic exists: {}", topic, e);
            return false;
        }
    }

    // ==================== 消费者组管理 ====================

    @Override
    public List<ConsumerGroupInfo> getConsumerGroups(String topic) {
        try {
            RStream<String, Object> stream = redissonClient.getStream(topic);

            if (!stream.isExists()) {
                return Collections.emptyList();
            }

            List<StreamGroup> groups = stream.listGroups();

            return groups.stream()
                    .map(group -> ConsumerGroupInfo.builder()
                            .name(group.getName())
                            .consumers(group.getConsumers())
                            .pending(group.getPending())
                            .lastDeliveredId(group.getLastDeliveredId())
                            .consumerList(Collections.emptyList()) // 需要单独调用才能获取详细信息
                            .build())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to get consumer groups for topic: {}", topic, e);
            return Collections.emptyList();
        }
    }

    @Override
    public ConsumerGroupStats getConsumerGroupStats(String topic, String group) {
        try {
            RStream<String, Object> stream = redissonClient.getStream(topic);

            if (!stream.isExists()) {
                return null;
            }

            // 获取消费者组信息
            List<StreamGroup> groups = stream.listGroups();
            StreamGroup targetGroup = groups.stream()
                    .filter(g -> g.getName().equals(group))
                    .findFirst()
                    .orElse(null);

            if (targetGroup == null) {
                return null;
            }

            // 获取 Pending 消息数量
            long pendingCount = targetGroup.getPending();

            // 简单的 lag 计算：获取队列长度作为估算
            long lag = stream.size();

            return ConsumerGroupStats.builder()
                    .groupName(group)
                    .topic(topic)
                    .pendingCount(pendingCount)
                    .consumerCount(targetGroup.getConsumers())
                    .lag(lag)
                    .hasActiveConsumers(targetGroup.getConsumers() > 0)
                    .slowestConsumer(null) // 需要更详细的 pending 信息才能确定
                    .maxIdleTime(0)
                    .build();

        } catch (Exception e) {
            log.error("Failed to get consumer group stats for topic: {}, group: {}", topic, group, e);
            return null;
        }
    }

    @Override
    public boolean consumerGroupExists(String topic, String group) {
        try {
            RStream<String, Object> stream = redissonClient.getStream(topic);

            if (!stream.isExists()) {
                return false;
            }

            List<StreamGroup> groups = stream.listGroups();
            return groups.stream().anyMatch(g -> g.getName().equals(group));

        } catch (Exception e) {
            log.error("Failed to check if consumer group exists: topic={}, group={}", topic, group, e);
            return false;
        }
    }

    // ==================== Pending 消息管理 ====================

    @Override
    public List<PendingMessage> getPendingMessages(String topic, String group, int limit) {
        try {
            RStream<String, Object> stream = redissonClient.getStream(topic);

            if (!stream.isExists()) {
                return Collections.emptyList();
            }

            // Redisson 的 listPending 返回 List<PendingEntry>
            @SuppressWarnings("deprecation")
            List<PendingEntry> pendingEntries = stream.listPending(group,
                    StreamMessageId.MIN, StreamMessageId.MAX, limit);

            return pendingEntries.stream()
                    .map(entry -> PendingMessage.builder()
                            .messageId(entry.getId())
                            .consumerName(entry.getConsumerName())
                            .idleTime(Duration.ofMillis(entry.getIdleTime()))
                            .deliveryCount(entry.getLastTimeDelivered())
                            .firstDeliveryTime(0) // 无法直接获取
                            .build())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to get pending messages for topic: {}, group: {}", topic, group, e);
            return Collections.emptyList();
        }
    }

    @Override
    public long getPendingCount(String topic, String group) {
        try {
            RStream<String, Object> stream = redissonClient.getStream(topic);

            if (!stream.isExists()) {
                return 0;
            }

            List<StreamGroup> groups = stream.listGroups();
            StreamGroup targetGroup = groups.stream()
                    .filter(g -> g.getName().equals(group))
                    .findFirst()
                    .orElse(null);

            return targetGroup != null ? targetGroup.getPending() : 0L;

        } catch (Exception e) {
            log.error("Failed to get pending count for topic: {}, group: {}", topic, group, e);
            return 0;
        }
    }

    // ==================== 运维操作 ====================

    @Override
    public long trimQueue(String topic, long maxLen) {
        try {
            RStream<String, Object> stream = redissonClient.getStream(topic);

            if (!stream.isExists()) {
                log.warn("Topic does not exist: {}", topic);
                return 0;
            }

            long originalSize = stream.size();

            if (originalSize <= maxLen) {
                return 0; // 无需裁剪
            }

            // 读取最老的消息并删除
            long toDelete = originalSize - maxLen;
            @SuppressWarnings("deprecation")
            Map<StreamMessageId, Map<String, Object>> oldMessages =
                    stream.range((int) toDelete, StreamMessageId.MIN, StreamMessageId.MAX);

            int deleted = 0;
            for (StreamMessageId msgId : oldMessages.keySet()) {
                stream.remove(msgId);
                deleted++;
            }

            log.info("Trimmed queue {}: {} messages deleted (kept {})", topic, deleted, stream.size());
            return deleted;

        } catch (Exception e) {
            log.error("Failed to trim queue: {}", topic, e);
            return 0;
        }
    }

    @Override
    public long trimQueueByAge(String topic, Duration maxAge) {
        try {
            RStream<String, Object> stream = redissonClient.getStream(topic);

            if (!stream.isExists()) {
                log.warn("Topic does not exist: {}", topic);
                return 0;
            }

            long originalSize = stream.size();

            // 计算最小消息ID（基于时间）
            long minTimestamp = Instant.now().minus(maxAge).toEpochMilli();

            // 读取所有消息，删除过期的
            @SuppressWarnings("deprecation")
            Map<StreamMessageId, Map<String, Object>> allMessages =
                    stream.range(Integer.MAX_VALUE, StreamMessageId.MIN, StreamMessageId.MAX);

            int deleted = 0;
            for (StreamMessageId msgId : allMessages.keySet()) {
                // 从 messageId 中提取时间戳 (格式: "timestamp-sequence")
                long msgTimestamp = Long.parseLong(msgId.toString().split("-")[0]);
                if (msgTimestamp < minTimestamp) {
                    stream.remove(msgId);
                    deleted++;
                }
            }

            log.info("Trimmed queue {} by age ({}): {} messages deleted", topic, maxAge, deleted);
            return deleted;

        } catch (Exception e) {
            log.error("Failed to trim queue by age: {}", topic, e);
            return 0;
        }
    }

    @Override
    public boolean deleteTopic(String topic) {
        try {
            RKeys keys = redissonClient.getKeys();
            long deleted = keys.delete(topic);

            if (deleted > 0) {
                // 从注册表中移除
                topicRegistry.unregisterTopic(topic);
                log.info("Deleted topic: {}", topic);
                return true;
            } else {
                log.warn("Topic not found: {}", topic);
                return false;
            }

        } catch (Exception e) {
            log.error("Failed to delete topic: {}", topic, e);
            return false;
        }
    }

    @Override
    public boolean deleteConsumerGroup(String topic, String group) {
        try {
            RStream<String, Object> stream = redissonClient.getStream(topic);

            if (!stream.isExists()) {
                log.warn("Topic does not exist: {}", topic);
                return false;
            }

            stream.removeGroup(group);
            log.info("Deleted consumer group: topic={}, group={}", topic, group);
            return true;

        } catch (Exception e) {
            log.error("Failed to delete consumer group: topic={}, group={}", topic, group, e);
            return false;
        }
    }

    @Override
    public boolean resetConsumerGroupOffset(String topic, String group, String messageId) {
        try {
            RStream<String, Object> stream = redissonClient.getStream(topic);

            if (!stream.isExists()) {
                log.warn("Topic does not exist: {}", topic);
                return false;
            }

            StreamMessageId targetId;
            if ("0".equals(messageId)) {
                targetId = StreamMessageId.MIN;
            } else if ("$".equals(messageId)) {
                targetId = StreamMessageId.MAX;
            } else {
                // 解析格式: "timestamp-sequence"
                String[] parts = messageId.split("-");
                if (parts.length == 2) {
                    targetId = new StreamMessageId(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
                } else {
                    targetId = new StreamMessageId(Long.parseLong(messageId));
                }
            }

            // Redisson 的 Stream API 需要先删除消费者组再重新创建来重置位置
            try {
                stream.removeGroup(group);
                stream.createGroup(StreamCreateGroupArgs.name(group).id(targetId));
                log.info("Reset consumer group offset: topic={}, group={}, messageId={}",
                        topic, group, messageId);
                return true;
            } catch (Exception e) {
                log.error("Failed to reset consumer group offset", e);
                return false;
            }

        } catch (Exception e) {
            log.error("Failed to reset consumer group offset: topic={}, group={}, messageId={}",
                    topic, group, messageId, e);
            return false;
        }
    }
}
