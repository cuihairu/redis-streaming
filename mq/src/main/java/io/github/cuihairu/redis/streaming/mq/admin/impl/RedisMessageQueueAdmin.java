package io.github.cuihairu.redis.streaming.mq.admin.impl;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.TopicRegistry;
import io.github.cuihairu.redis.streaming.mq.admin.model.*;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
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
    private final TopicPartitionRegistry partitionRegistry;

    public RedisMessageQueueAdmin(RedissonClient redissonClient) {
        this(redissonClient, null);
    }

    public RedisMessageQueueAdmin(RedissonClient redissonClient, MqOptions options) {
        this.redissonClient = redissonClient;
        String prefix = options != null ? options.getKeyPrefix() : TopicRegistry.DEFAULT_PREFIX;
        this.topicRegistry = new TopicRegistry(redissonClient, prefix);
        this.partitionRegistry = new TopicPartitionRegistry(redissonClient);
    }

    // ==================== 队列信息查询 ====================

    @Override
    public QueueInfo getQueueInfo(String topic) {
        try {
            int pc = partitionRegistry.getPartitionCount(topic);
            if (pc <= 1) {
                // Prefer partition stream key for pc=1; fallback to legacy topic key for compatibility
                RStream<String, Object> stream = redissonClient.getStream(StreamKeys.partitionStream(topic, 0));
                if (!stream.isExists()) {
                    stream = redissonClient.getStream(topic);
                    if (!stream.isExists()) {
                        return QueueInfo.builder().topic(topic).exists(false).length(0).consumerGroupCount(0).build();
                    }
                }
                StreamInfo<String, Object> info = stream.getInfo();
                return QueueInfo.builder()
                        .topic(topic).exists(true)
                        .length(stream.size())
                        .consumerGroupCount(info.getGroups())
                        .firstMessageId(info.getFirstEntry() != null ? info.getFirstEntry().getId() : null)
                        .lastMessageId(info.getLastGeneratedId())
                        .createdAt(null)
                        .lastUpdatedAt(info.getLastGeneratedId() != null ?
                                Instant.ofEpochMilli(Long.parseLong(info.getLastGeneratedId().toString().split("-")[0])) : null)
                        .build();
            } else {
                long total = 0;
                int groupCount = 0;
                StreamMessageId first = null;
                StreamMessageId last = null;
                for (int i = 0; i < pc; i++) {
                    String key = StreamKeys.partitionStream(topic, i);
                    RStream<String, Object> s = redissonClient.getStream(key);
                    if (!s.isExists()) continue;
                    total += s.size();
                    StreamInfo<String, Object> info = s.getInfo();
                    groupCount = Math.max(groupCount, info.getGroups());
                    if (info.getFirstEntry() != null) first = info.getFirstEntry().getId();
                    if (info.getLastGeneratedId() != null) last = info.getLastGeneratedId();
                }
                return QueueInfo.builder()
                        .topic(topic).exists(true)
                        .length(total)
                        .consumerGroupCount(groupCount)
                        .firstMessageId(first)
                        .lastMessageId(last)
                        .createdAt(null)
                        .lastUpdatedAt(last != null ?
                                Instant.ofEpochMilli(Long.parseLong(last.toString().split("-")[0])) : null)
                        .build();
            }

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
            RStream<String, Object> s0 = redissonClient.getStream(StreamKeys.partitionStream(topic, 0));
            if (s0.isExists()) return true;
            int pc = new TopicPartitionRegistry(redissonClient).getPartitionCount(topic);
            if (pc <= 1) return s0.isExists();
            for (int i = 0; i < pc; i++) {
                if (redissonClient.getStream(StreamKeys.partitionStream(topic, i)).isExists()) return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Failed to check if topic exists: {}", topic, e);
            return false;
        }
    }

    // ==================== 消费者组管理 ====================

    @Override
    public List<ConsumerGroupInfo> getConsumerGroups(String topic) {
        try {
            int pc = partitionRegistry.getPartitionCount(topic);
            Map<String, ConsumerGroupInfo> map = new HashMap<>();
            if (pc <= 1) {
                RStream<String, Object> stream = redissonClient.getStream(StreamKeys.partitionStream(topic, 0));
                if (!stream.isExists()) return Collections.emptyList();
                for (StreamGroup g : stream.listGroups()) {
                    map.put(g.getName(), ConsumerGroupInfo.builder()
                            .name(g.getName())
                            .consumers(g.getConsumers())
                            .pending(g.getPending())
                            .lastDeliveredId(g.getLastDeliveredId())
                            .consumerList(Collections.emptyList())
                            .build());
                }
            } else {
                for (int i = 0; i < pc; i++) {
                    RStream<String, Object> s = redissonClient.getStream(StreamKeys.partitionStream(topic, i));
                    if (!s.isExists()) continue;
                    for (StreamGroup g : s.listGroups()) {
                        ConsumerGroupInfo agg = map.get(g.getName());
                        if (agg == null) {
                            agg = ConsumerGroupInfo.builder()
                                    .name(g.getName())
                                    .consumers(g.getConsumers())
                                    .pending(g.getPending())
                                    .lastDeliveredId(g.getLastDeliveredId())
                                    .consumerList(Collections.emptyList())
                                    .build();
                        } else {
                            agg.setConsumers(agg.getConsumers() + g.getConsumers());
                            agg.setPending(agg.getPending() + g.getPending());
                            agg.setLastDeliveredId(g.getLastDeliveredId());
                        }
                        map.put(g.getName(), agg);
                    }
                }
            }
            return new ArrayList<>(map.values());

        } catch (Exception e) {
            log.error("Failed to get consumer groups for topic: {}", topic, e);
            return Collections.emptyList();
        }
    }

    @Override
    public ConsumerGroupStats getConsumerGroupStats(String topic, String group) {
        try {
            int pc = partitionRegistry.getPartitionCount(topic);
            long pending = 0;
            int consumers = 0;
            long lag = 0;
            boolean hasActive = false;
            for (int i = 0; i < pc; i++) {
                RStream<String, Object> s = redissonClient.getStream(pc <= 1 ? StreamKeys.partitionStream(topic, 0) : StreamKeys.partitionStream(topic, i));
                if (!s.isExists()) continue;
                List<StreamGroup> groups = s.listGroups();
                StreamGroup g = groups.stream().filter(x -> x.getName().equals(group)).findFirst().orElse(null);
                if (g != null) {
                    pending += g.getPending();
                    consumers += g.getConsumers();
                    StreamMessageId last = s.getInfo().getLastGeneratedId();
                    if (last != null && g.getLastDeliveredId() != null) {
                        // Approximate lag by comparing message ids timestamp part
                        long lastTs = Long.parseLong(last.toString().split("-")[0]);
                        long deliveredTs = Long.parseLong(g.getLastDeliveredId().toString().split("-")[0]);
                        lag += Math.max(0, lastTs - deliveredTs);
                    }
                    hasActive |= g.getConsumers() > 0;
                }
            }
            return ConsumerGroupStats.builder()
                    .groupName(group)
                    .topic(topic)
                    .pendingCount(pending)
                    .consumerCount(consumers)
                    .lag(lag)
                    .hasActiveConsumers(hasActive)
                    .slowestConsumer(null)
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
            int pc = partitionRegistry.getPartitionCount(topic);
            for (int i = 0; i < Math.max(1, pc); i++) {
                RStream<String, Object> s = redissonClient.getStream(StreamKeys.partitionStream(topic, i));
                if (!s.isExists()) continue;
                List<StreamGroup> groups = s.listGroups();
                if (groups.stream().anyMatch(g -> g.getName().equals(group))) return true;
            }
            return false;

        } catch (Exception e) {
            log.error("Failed to check if consumer group exists: topic={}, group={}", topic, group, e);
            return false;
        }
    }

    // ==================== Pending 消息管理 ====================

    @Override
    public List<PendingMessage> getPendingMessages(String topic, String group, int limit) {
        try {
            return getPendingMessages(topic, group, limit, io.github.cuihairu.redis.streaming.mq.admin.model.PendingSort.IDLE, true, 0);

        } catch (Exception e) {
            log.error("Failed to get pending messages for topic: {}, group: {}", topic, group, e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<PendingMessage> getPendingMessages(String topic, String group, int limit,
                                                   io.github.cuihairu.redis.streaming.mq.admin.model.PendingSort sort,
                                                   boolean desc,
                                                   long minIdleMs) {
        try {
            int pc = Math.max(1, partitionRegistry.getPartitionCount(topic));
            int samplePerPartition = Math.min(Math.max(1, limit), 200);
            List<PendingEntry> all = new ArrayList<>();
            for (int i = 0; i < pc; i++) {
                RStream<String, Object> stream = redissonClient.getStream(StreamKeys.partitionStream(topic, i));
                if (!stream.isExists()) continue;
                @SuppressWarnings("deprecation")
                List<PendingEntry> list = stream.listPending(group, StreamMessageId.MIN, StreamMessageId.MAX, samplePerPartition);
                for (PendingEntry p : list) {
                    if (minIdleMs > 0 && p.getIdleTime() < minIdleMs) continue;
                    all.add(p);
                }
            }

            Comparator<PendingEntry> cmp;
            if (sort == io.github.cuihairu.redis.streaming.mq.admin.model.PendingSort.DELIVERIES) {
                cmp = Comparator.comparingLong(PendingEntry::getLastTimeDelivered);
            } else if (sort == io.github.cuihairu.redis.streaming.mq.admin.model.PendingSort.ID) {
                cmp = Comparator.comparing(p -> p.getId().toString());
            } else {
                cmp = Comparator.comparingLong(PendingEntry::getIdleTime);
            }
            if (desc) cmp = cmp.reversed();
            all.sort(cmp);

            List<PendingMessage> result = new ArrayList<>();
            for (int i = 0; i < all.size() && result.size() < limit; i++) {
                PendingEntry entry = all.get(i);
                result.add(PendingMessage.builder()
                        .messageId(entry.getId())
                        .consumerName(entry.getConsumerName())
                        .idleTime(Duration.ofMillis(entry.getIdleTime()))
                        .deliveryCount(entry.getLastTimeDelivered())
                        .firstDeliveryTime(0)
                        .build());
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to get pending messages(sorted) for topic: {}, group: {}", topic, group, e);
            return Collections.emptyList();
        }
    }

    @Override
    public long getPendingCount(String topic, String group) {
        try {
            int pc = partitionRegistry.getPartitionCount(topic);
            long total = 0;
            for (int i = 0; i < pc; i++) {
                RStream<String, Object> s = redissonClient.getStream(pc <= 1 ? StreamKeys.partitionStream(topic, 0) : StreamKeys.partitionStream(topic, i));
                if (!s.isExists()) continue;
                List<StreamGroup> groups = s.listGroups();
                StreamGroup target = groups.stream().filter(g -> g.getName().equals(group)).findFirst().orElse(null);
                if (target != null) total += target.getPending();
            }
            return total;

        } catch (Exception e) {
            log.error("Failed to get pending count for topic: {}, group: {}", topic, group, e);
            return 0;
        }
    }

    // ==================== 运维操作 ====================

    @Override
    public long trimQueue(String topic, long maxLen) {
        try {
            int pc = partitionRegistry.getPartitionCount(topic);
            if (pc <= 1) pc = 1;
            long per = Math.max(0, maxLen / pc);
            long deletedTotal = 0;
            for (int i = 0; i < pc; i++) {
                RStream<String, Object> s = redissonClient.getStream(pc == 1 ? StreamKeys.partitionStream(topic, 0) : StreamKeys.partitionStream(topic, i));
                if (!s.isExists()) continue;
                long size = s.size();
                if (size > per) {
                    long toDelete = size - per;
                    int batch = (int) Math.min(Integer.MAX_VALUE, toDelete);
                    @SuppressWarnings("deprecation")
                    Map<StreamMessageId, Map<String, Object>> old = s.range(batch, StreamMessageId.MIN, StreamMessageId.MAX);
                    int removed = 0;
                    for (StreamMessageId id : old.keySet()) {
                        s.remove(id);
                        removed++;
                    }
                    deletedTotal += removed;
                }
            }
            log.info("Trimmed queue {} (pc={}): ~{} messages deleted", topic, pc, deletedTotal);
            return deletedTotal;

        } catch (Exception e) {
            log.error("Failed to trim queue: {}", topic, e);
            return 0;
        }
    }

    @Override
    public long trimQueueByAge(String topic, Duration maxAge) {
        try {
            int pc = partitionRegistry.getPartitionCount(topic);
            if (pc <= 1) pc = 1;
            long minTs = Instant.now().minus(maxAge).toEpochMilli();
            long deletedTotal = 0;
            for (int i = 0; i < pc; i++) {
                RStream<String, Object> s = redissonClient.getStream(pc == 1 ? StreamKeys.partitionStream(topic, 0) : StreamKeys.partitionStream(topic, i));
                if (!s.isExists()) continue;
                // Fallback: bounded range scan and remove (avoid relying on StreamTrimArgs API variations)
                @SuppressWarnings("deprecation")
                Map<StreamMessageId, Map<String, Object>> old = s.range(Integer.MAX_VALUE, StreamMessageId.MIN, new StreamMessageId(minTs));
                for (StreamMessageId id : old.keySet()) {
                    s.remove(id);
                    deletedTotal++;
                }
            }
            log.info("Trimmed queue {} by age {}: ~{} messages deleted", topic, maxAge, deletedTotal);
            return deletedTotal;

        } catch (Exception e) {
            log.error("Failed to trim queue by age: {}", topic, e);
            return 0;
        }
    }

    @Override
    public boolean deleteTopic(String topic) {
        try {
            RKeys keys = redissonClient.getKeys();
            int pc = Math.max(1, partitionRegistry.getPartitionCount(topic));

            // Collect all keys to delete: partition streams, DLQ, meta, partitions set, and legacy bare topic key
            java.util.List<String> toDelete = new java.util.ArrayList<>();
            for (int i = 0; i < pc; i++) {
                toDelete.add(StreamKeys.partitionStream(topic, i));
            }
            toDelete.add(StreamKeys.dlq(topic));
            toDelete.add(StreamKeys.topicMeta(topic));
            toDelete.add(StreamKeys.topicPartitionsSet(topic));
            // legacy single-stream key (if ever used)
            toDelete.add(topic);

            long deleted = 0;
            for (String k : toDelete) {
                try { deleted += keys.delete(k); } catch (Exception ignore) {}
            }

            // Remove from registry regardless of deleted count for idempotency
            topicRegistry.unregisterTopic(topic);
            log.info("Deleted topic artifacts for '{}': partitions={}, removedKeys={} (best-effort)", topic, pc, deleted);
            return deleted > 0;

        } catch (Exception e) {
            log.error("Failed to delete topic: {}", topic, e);
            return false;
        }
    }

    @Override
    public boolean deleteConsumerGroup(String topic, String group) {
        try {
            int pc = Math.max(1, partitionRegistry.getPartitionCount(topic));
            boolean attempted = false;
            int removed = 0;
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            for (int i = 0; i < pc; i++) {
                keys.add(StreamKeys.partitionStream(topic, i));
            }
            // Fallback: if meta not initialized (pc==1), also scan for any existing partition streams
            if (pc <= 1) {
                try {
                    String pat = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.streamPrefix()
                            + ":" + topic + ":p:*";
                    try {
                        for (String k : redissonClient.getKeys().getKeys()) {
                            if (k != null && k.startsWith(io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.streamPrefix() + ":" + topic + ":p:")) {
                                keys.add(k);
                            }
                        }
                    } catch (Exception ignore) {}
                } catch (Exception ignore) {}
            }
            for (String key : keys) {
                RStream<String, Object> s = redissonClient.getStream(key);
                attempted = true;
                try {
                    s.removeGroup(group);
                    removed++;
                } catch (Exception ignore) {}
            }
            if (attempted) {
                log.info("Deleted consumer group across {} partitions: topic={}, group={}", pc, topic, group);
            }
            // Return true if we attempted removal (best-effort); exact removal count may be zero when group absent
            return attempted;

        } catch (Exception e) {
            log.error("Failed to delete consumer group: topic={}, group={}", topic, group, e);
            return false;
        }
    }

    @Override
    public boolean resetConsumerGroupOffset(String topic, String group, String messageId) {
        try {
            int pc = Math.max(1, partitionRegistry.getPartitionCount(topic));
            StreamMessageId targetId;
            if ("0".equals(messageId)) {
                targetId = StreamMessageId.MIN;
            } else if ("$".equals(messageId)) {
                targetId = StreamMessageId.MAX;
            } else {
                String[] parts = messageId.split("-");
                if (parts.length == 2) {
                    targetId = new StreamMessageId(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
                } else {
                    targetId = new StreamMessageId(Long.parseLong(messageId));
                }
            }

            boolean any = false;
            for (int i = 0; i < pc; i++) {
                RStream<String, Object> s = redissonClient.getStream(StreamKeys.partitionStream(topic, i));
                if (!s.isExists()) continue;
                any = true;
                try {
                    s.removeGroup(group);
                } catch (Exception ignore) {}
                s.createGroup(StreamCreateGroupArgs.name(group).id(targetId));
            }
            if (any) {
                log.info("Reset consumer group offset across {} partitions: topic={}, group={}, id={}", pc, topic, group, messageId);
                return true;
            }
            log.warn("No partitions exist for topic {}", topic);
            return false;
        } catch (Exception e) {
            log.error("Failed to reset consumer group offset: topic={}, group={}, messageId={}", topic, group, messageId, e);
            return false;
        }
    }

    @Override
    public boolean updatePartitionCount(String topic, int newPartitionCount) {
        try {
            boolean ok = partitionRegistry.updatePartitionCount(topic, newPartitionCount);
            if (ok) {
                log.info("Partition count updated for {} to {}", topic, newPartitionCount);
            } else {
                log.warn("Partition count not updated for {} (maybe not increased)", topic);
            }
            return ok;
        } catch (Exception e) {
            log.error("Failed to update partition count for {} to {}", topic, newPartitionCount, e);
            return false;
        }
    }
}
