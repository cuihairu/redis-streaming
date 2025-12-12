package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerRouter;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default Broker implementation: centralizes routing + persistence over Redis (by default).
 */
public class DefaultBroker implements Broker {
    private static final Logger log = LoggerFactory.getLogger(DefaultBroker.class);
    private final RedissonClient redissonClient;
    private final MqOptions options;
    private final BrokerRouter router;
    private final BrokerPersistence persistence;
    private final TopicPartitionRegistry partitionRegistry;

    public DefaultBroker(RedissonClient redissonClient,
                         MqOptions options,
                         BrokerRouter router,
                         BrokerPersistence persistence) {
        this.redissonClient = redissonClient;
        this.options = options == null ? MqOptions.builder().build() : options;
        this.router = router;
        this.persistence = persistence;
        this.partitionRegistry = new TopicPartitionRegistry(redissonClient);
    }

    @Override
    public String produce(Message message) {
        // Ensure partitions metadata exists
        partitionRegistry.ensureTopic(message.getTopic(), options.getDefaultPartitionCount());
        int partitions = partitionRegistry.getPartitionCount(message.getTopic());
        int pid = router.routePartition(message.getTopic(), message.getKey(), message.getHeaders(), partitions);
        String id = persistence.append(message.getTopic(), pid, message);
        if (id != null) {
            message.setId(id);
            MqMetrics.get().incProduced(message.getTopic(), pid);
        }
        return id;
    }

    @Override
    public java.util.List<io.github.cuihairu.redis.streaming.mq.broker.BrokerRecord> readGroup(String topic, String consumerGroup, String consumerName, int partitionId, int count, long timeoutMs) {
        String streamKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, partitionId);
        org.redisson.api.RStream<String, Object> stream = redissonClient.getStream(streamKey, org.redisson.client.codec.StringCodec.INSTANCE);
        // Best-effort: ensure the consumer group exists on this stream before reading. This avoids races
        // where subscription hasn't created the group yet when the first message arrives.
        try {
            boolean exists = false;
            try {
                java.util.List<org.redisson.api.StreamGroup> groups = stream.listGroups();
                if (groups != null) {
                    for (org.redisson.api.StreamGroup g : groups) {
                        if (consumerGroup.equals(g.getName())) { exists = true; break; }
                    }
                }
            } catch (Exception ignore) {}
            if (!exists) {
                try {
                    stream.createGroup(org.redisson.api.stream.StreamCreateGroupArgs
                            .name(consumerGroup)
                            .id(org.redisson.api.StreamMessageId.MIN)
                            .makeStream());
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        java.time.Duration timeout = java.time.Duration.ofMillis(timeoutMs < 0 ? 0 : timeoutMs);
        java.util.Map<org.redisson.api.StreamMessageId, java.util.Map<String, Object>> messages = stream.readGroup(
                consumerGroup, consumerName,
                org.redisson.api.stream.StreamReadGroupArgs.neverDelivered().count(Math.max(1, count)).timeout(timeout)
        );
        java.util.List<io.github.cuihairu.redis.streaming.mq.broker.BrokerRecord> out = new java.util.ArrayList<>(messages.size());
        for (java.util.Map.Entry<org.redisson.api.StreamMessageId, java.util.Map<String, Object>> e : messages.entrySet()) {
            out.add(new io.github.cuihairu.redis.streaming.mq.broker.BrokerRecord(e.getKey().toString(), e.getValue()));
        }
        return out;
    }

    @Override
    public void ack(String topic, String consumerGroup, int partitionId, String messageId) {
        String streamKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, partitionId);
        org.redisson.api.RStream<String, Object> stream = redissonClient.getStream(streamKey, org.redisson.client.codec.StringCodec.INSTANCE);
        String policy = options.getAckDeletePolicy();
        if (policy == null) policy = "none";
        policy = policy.toLowerCase();
        // Ack is not best-effort: swallowing failures causes invisible pending buildup and inconsistent cleanup.
        try {
            stream.ack(consumerGroup, parseStreamId(messageId));
        } catch (Exception e) {
            log.warn("Broker ack failed: topic={} group={} partition={} id={}", topic, consumerGroup, partitionId, messageId, e);
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException("Broker ack failed", e);
        }

        switch (policy) {
            case "immediate":
                // Single-group safe: delete immediately after ack
                try { stream.remove(parseStreamId(messageId)); } catch (Exception e) {
                    log.debug("Broker immediate delete failed: {} {}", streamKey, messageId, e);
                }
                break;
            case "all-groups-ack": {
                // Collect group ack into ack-set; if size reaches active group count, delete the entry
                String ackKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.ackSet(topic, partitionId, messageId);
                org.redisson.api.RSet<String> ackset = redissonClient.getSet(ackKey, org.redisson.client.codec.StringCodec.INSTANCE);
                try { ackset.add(consumerGroup); } catch (Exception e) {
                    log.debug("Ack-set add failed: {}", ackKey, e);
                }
                try {
                    redissonClient.getBucket(ackKey, org.redisson.client.codec.StringCodec.INSTANCE)
                            .expire(java.time.Duration.ofSeconds(Math.max(1, options.getAcksetTtlSec())));
                } catch (Exception e) {
                    log.debug("Ack-set expire failed: {}", ackKey, e);
                }
                // Compute active groups: groups present on stream AND having an active lease on this partition
                int active = 0;
                try {
                    java.util.List<org.redisson.api.StreamGroup> groups = stream.listGroups();
                    for (org.redisson.api.StreamGroup g : groups) {
                        String leaseKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.lease(topic, g.getName(), partitionId);
                        boolean live = redissonClient.getBucket(leaseKey, org.redisson.client.codec.StringCodec.INSTANCE).isExists();
                        if (live) active++;
                    }
                } catch (Exception e) {
                    log.debug("Failed to compute active groups: {}:p:{}", topic, partitionId, e);
                }
                try {
                    if (active > 0 && ackset.size() >= active) {
                        stream.remove(parseStreamId(messageId));
                        try { ackset.delete(); } catch (Exception e) { log.debug("Ack-set delete failed: {}", ackKey, e); }
                    }
                } catch (Exception e) {
                    log.debug("all-groups-ack delete failed: {} {}", streamKey, messageId, e);
                }
                break;
            }
            case "none":
            default:
                // do nothing
                break;
        }
    }

    private org.redisson.api.StreamMessageId parseStreamId(String id) {
        try {
            if (id == null) return org.redisson.api.StreamMessageId.MIN;
            String[] parts = id.split("-", 2);
            if (parts.length == 2) {
                long ms = Long.parseLong(parts[0]);
                long seq = Long.parseLong(parts[1]);
                return new org.redisson.api.StreamMessageId(ms, seq);
            }
            long ms = Long.parseLong(id);
            return new org.redisson.api.StreamMessageId(ms);
        } catch (Exception e) {
            return org.redisson.api.StreamMessageId.MIN;
        }
    }
}
