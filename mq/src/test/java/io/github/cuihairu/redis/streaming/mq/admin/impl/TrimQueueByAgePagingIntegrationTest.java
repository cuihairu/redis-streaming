package io.github.cuihairu.redis.streaming.mq.admin.impl;

import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MQ-13 on real Redis: trimQueueByAge now pages its old-id scan (bounded COUNT + id
 * cursor). With the page-size seam set below the entry count the trim must span multiple
 * pages and still delete every aged entry exactly once.
 */
@Tag("integration")
class TrimQueueByAgePagingIntegrationTest {

    private RedissonClient client;
    private String topic;

    @BeforeEach
    void setUp() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        client = Redisson.create(cfg);
        topic = "trimage-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        client.getKeys().deleteByPattern(StreamKeys.partitionStream(topic, 0));
        client.shutdown();
    }

    @Test
    void multiPageTrimByAgeDeletesEverythingAcrossPages() {
        RStream<String, Object> stream = client.getStream(StreamKeys.partitionStream(topic, 0), StringCodec.INSTANCE);
        for (int i = 0; i < 12; i++) {
            stream.add(StreamAddArgs.entry("payload", "p" + i));
        }
        assertEquals(12L, stream.size());

        System.setProperty("mq.admin.test.trimAgePageSize", "5"); // 12 entries -> pages of 5,5,2
        try {
            RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(client);
            // negative age -> end bound is in the future, every entry is "aged out"
            long deleted = admin.trimQueueByAge(topic, Duration.ofSeconds(-1));
            assertEquals(12L, deleted, "every entry must be deleted exactly once across pages");
        } finally {
            System.clearProperty("mq.admin.test.trimAgePageSize");
        }
        assertEquals(0L, stream.size(), "the stream must be empty after the paged trim");
    }
}
