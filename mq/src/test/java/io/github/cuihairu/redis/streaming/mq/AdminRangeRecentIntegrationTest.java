package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.model.MessageEntry;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class AdminRangeRecentIntegrationTest {

    @Test
    void listRecentAndRangeReturnEntries() throws Exception {
        String topic = "range-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(2).consumerPollTimeoutMs(50).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer producer = factory.createProducer();
            // produce across keys to distribute to partitions
            for (int i=0;i<20;i++) producer.send(topic, "k" + (i%4), "v"+i).get();

            MessageQueueAdmin admin = new RedisMessageQueueAdmin(client, opts);
            List<MessageEntry> recent = admin.listRecent(topic, 5);
            assertNotNull(recent);
            assertFalse(recent.isEmpty());

            // Range ascending for partition 0
            List<MessageEntry> asc = admin.range(topic, 0, "0-0", "+", 10, false);
            List<MessageEntry> desc = admin.range(topic, 0, "+", "-", 10, true);
            assertNotNull(asc); assertNotNull(desc);
            if (!asc.isEmpty() && !desc.isEmpty()) {
                String minId = asc.stream().map(MessageEntry::getId).min(Comparator.naturalOrder()).orElse("0-0");
                String maxId = asc.stream().map(MessageEntry::getId).max(Comparator.naturalOrder()).orElse("0-0");
                assertTrue(desc.get(0).getId().compareTo(minId) >= 0);
                assertTrue(desc.get(0).getId().compareTo(maxId) >= 0 || desc.size()==1);
            }
        } finally { client.shutdown(); }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

