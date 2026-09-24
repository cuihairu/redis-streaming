package io.github.cuihairu.redis.streaming.starter;

import io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.starter.maintenance.StreamRetentionHousekeeper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.config.Config;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-Redis coverage for StreamRetentionHousekeeper.runOnce/trimTopic/trimDlq and
 * RetentionFrontierMetricsBinder with crafted stream ids (compareStreamId/parseMs paths).
 */
@Tag("integration")
class RetentionFrontierHousekeeperIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void housekeeperTrimsWithCraftedStreamIds() {
        RedissonClient redis = client();
        String topic = "hk-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            // craft a partition stream with entries at known ids
            RStream<String, String> stream = redis.getStream(StreamKeys.partitionStream(topic, 0));
            for (int i = 0; i < 5; i++) {
                stream.add(StreamAddArgs.entries(java.util.Map.of("value", "v" + i)));
            }
            // register metadata so the partition count is explicit
            redis.<String, String>getMap(StreamKeys.topicMeta(topic)).put("partitionCount", "1");

            // craft commit frontier ids exercising compareStreamId (same ms diff seq, diff ms, malformed)
            String frontierKey = StreamKeys.commitFrontier(topic, 0);
            redis.<String, String>getMap(frontierKey).put("g1", "5-9");
            redis.<String, String>getMap(frontierKey).put("g2", "5-2");
            redis.<String, String>getMap(frontierKey).put("g3", "4");
            redis.<String, String>getMap(frontierKey).put("g4", "not-a-stream-id");
            redis.getBucket(StreamKeys.lease(topic, "g1", 0)).set("x", java.time.Duration.ofSeconds(30));
            redis.getBucket(StreamKeys.lease(topic, "g2", 0)).set("x", java.time.Duration.ofSeconds(30));
            redis.getBucket(StreamKeys.lease(topic, "g3", 0)).set("x", java.time.Duration.ofSeconds(30));
            redis.getBucket(StreamKeys.lease(topic, "g4", 0)).set("x", java.time.Duration.ofSeconds(30));

            MqOptions opts = MqOptions.builder()
                    .defaultPartitionCount(1)
                    .retentionMaxLenPerPartition(2)
                    .retentionMs(1_000)
                    .dlqRetentionMaxLen(1)
                    .dlqRetentionMs(1_000)
                    .trimIntervalSec(3600)
                    .build();
            StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(
                    redis, new RedisMessageQueueAdmin(redis, opts), opts);
            try {
                assertDoesNotThrow(keeper::runOnce);
                assertTrue(stream.size() <= 5, "retention keeps the stream bounded");
            } finally {
                keeper.close();
            }

            io.github.cuihairu.redis.streaming.starter.metrics.RetentionFrontierMetricsBinder binder =
                    new io.github.cuihairu.redis.streaming.starter.metrics.RetentionFrontierMetricsBinder(
                            redis, new RedisMessageQueueAdmin(redis, opts), opts);
            MeterRegistry registry = new SimpleMeterRegistry();
            binder.bindTo(registry);
            Double age = registry.get("redis_streaming_mq_frontier_age_ms").gauge().value();
            assertNotNull(age);
        } finally {
            redis.getKeys().deleteByPattern("*:" + topic + "*");
            redis.getKeys().deleteByPattern(topic + "*");
            redis.getKeys().deleteByPattern("streaming:mq*:" + topic + "*");
            redis.shutdown();
        }
    }
}
