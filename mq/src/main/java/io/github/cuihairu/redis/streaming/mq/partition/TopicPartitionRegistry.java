package io.github.cuihairu.redis.streaming.mq.partition;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Registry and metadata for partitioned topics. Falls back to single-partition when meta absent.
 */
@Slf4j
public class TopicPartitionRegistry {
    private static final String FIELD_PARTITION_COUNT = "partitionCount";

    private final RedissonClient redissonClient;

    public TopicPartitionRegistry(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** Ensure topic meta exists; if absent, initialize with given partitionCount. */
    public void ensureTopic(String topic, int partitionCount) {
        try {
            // Register topic name using configured control prefix
            redissonClient.getSet(StreamKeys.topicsRegistry(), org.redisson.client.codec.StringCodec.INSTANCE).add(topic);

            RMap<String, String> meta = redissonClient.getMap(StreamKeys.topicMeta(topic), org.redisson.client.codec.StringCodec.INSTANCE);
            if (!meta.containsKey(FIELD_PARTITION_COUNT)) {
                meta.put(FIELD_PARTITION_COUNT, Integer.toString(Math.max(1, partitionCount)));
                // Precompute partition keys set for convenience
                RSet<String> set = redissonClient.getSet(StreamKeys.topicPartitionsSet(topic), org.redisson.client.codec.StringCodec.INSTANCE);
                int pc = Math.max(1, partitionCount);
                for (int i = 0; i < pc; i++) {
                    set.add(StreamKeys.partitionStream(topic, i));
                }
                log.info("Initialized topic meta: {} partitions for topic {}", pc, topic);
            }
        } catch (Exception e) {
            log.error("Failed to ensure topic meta for {}", topic, e);
        }
    }

    /** Get partition count; default 1 if meta absent or unparsable. */
    public int getPartitionCount(String topic) {
        try {
            RMap<String, String> meta = redissonClient.getMap(StreamKeys.topicMeta(topic), org.redisson.client.codec.StringCodec.INSTANCE);
            String val = meta.get(FIELD_PARTITION_COUNT);
            if (val == null) {
                return 1;
            }
            int pc = Integer.parseInt(val);
            return pc <= 0 ? 1 : pc;
        } catch (Exception e) {
            log.warn("Failed to read partition count for {}, defaulting to 1", topic, e);
            return 1;
        }
    }

    /** List partition stream keys in order 0..P-1. */
    public List<String> listPartitionStreams(String topic) {
        int pc = getPartitionCount(topic);
        List<String> keys = new ArrayList<>(pc);
        for (int i = 0; i < pc; i++) {
            keys.add(StreamKeys.partitionStream(topic, i));
        }
        return keys;
    }

    /**
     * Update partition count (only increase). Adjusts metadata and partition set.
     */
    public boolean updatePartitionCount(String topic, int newCount) {
        try {
            if (newCount < 1) return false;
            RMap<String, String> meta = redissonClient.getMap(StreamKeys.topicMeta(topic), org.redisson.client.codec.StringCodec.INSTANCE);
            String val = meta.get(FIELD_PARTITION_COUNT);
            int old = 1;
            if (val != null) {
                try { old = Integer.parseInt(val); } catch (Exception ignore) { old = 1; }
            }
            if (newCount <= old) {
                return false; // only increase
            }
            meta.put(FIELD_PARTITION_COUNT, Integer.toString(newCount));
            RSet<String> set = redissonClient.getSet(StreamKeys.topicPartitionsSet(topic), org.redisson.client.codec.StringCodec.INSTANCE);
            for (int i = old; i < newCount; i++) {
                set.add(StreamKeys.partitionStream(topic, i));
            }
            log.info("Updated partition count for {}: {} -> {}", topic, old, newCount);
            return true;
        } catch (Exception e) {
            log.error("Failed to update partition count for {} to {}", topic, newCount, e);
            return false;
        }
    }
}
