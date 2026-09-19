package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for the DLQ stack: send/range/size/delete/clear/replay via
 * {@link RedisDeadLetterService} and background replay via {@link RedisDeadLetterConsumer}.
 */
@Tag("integration")
class DeadLetterStackIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    private static DeadLetterRecord record(String topic, String payload) {
        DeadLetterRecord r = new DeadLetterRecord();
        r.originalTopic = topic;
        r.payload = payload;
        r.retryCount = 1;
        r.maxRetries = 3;
        r.headers = new java.util.HashMap<>(Map.of("k", "v"));
        r.originalMessageId = "orig-" + payload;
        r.timestamp = Instant.now();
        return r;
    }

    @Test
    void serviceSendRangeReplayDeleteClear() {
        RedissonClient client = createClient();
        String topic = "dlq-svc-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> replayed = new CopyOnWriteArrayList<>();
        RedisDeadLetterService service = new RedisDeadLetterService(client,
                (t, pid, payload, headers, maxRetries) -> {
                    replayed.add(t + ":" + payload);
                    return true;
                });
        try {
            assertEquals(0L, service.size(topic));
            StreamMessageId id1 = service.send(record(topic, "p1"));
            StreamMessageId id2 = service.send(record(topic, "p2"));
            assertNotNull(id1);
            assertNotNull(id2);
            assertEquals(2L, service.size(topic));

            Map<StreamMessageId, Map<String, Object>> range = service.range(topic, 10);
            assertEquals(2, range.size());
            assertTrue(range.containsKey(id2));

            assertTrue(service.replay(topic, id1));
            assertTrue(replayed.contains(topic + ":p1"));
            assertFalse(service.replay(topic, new StreamMessageId(1, 1))); // unknown id

            assertTrue(service.delete(topic, id2));
            assertEquals(1L, service.size(topic));
            assertTrue(service.clear(topic) >= 0);
            assertEquals(0L, service.size(topic));
        } finally {
            client.getKeys().deleteByPattern("dlq*" + topic + "*");
            client.shutdown();
        }
    }

    @Test
    void consumerBackgroundReplay() throws Exception {
        RedissonClient client = createClient();
        String topic = "dlq-con-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> replayed = new CopyOnWriteArrayList<>();
        RedisDeadLetterService service = new RedisDeadLetterService(client,
                (t, pid, payload, headers, maxRetries) -> false); // service replay unused here
        service.send(record(topic, "auto"));

        RedisDeadLetterConsumer consumer = new RedisDeadLetterConsumer(client, "c-dlq", "g-dlq",
                (t, pid, payload, headers, maxRetries) -> true);
        DeadLetterConsumer.DeadLetterHandler handler = entry -> {
            replayed.add(entry.getOriginalTopic() + ":" + entry.getPayload());
            return DeadLetterConsumer.HandleResult.SUCCESS;
        };
        try {
            consumer.subscribe(topic, handler);
            consumer.start();
            assertTrue(consumer.isRunning());
            long deadline = System.currentTimeMillis() + 15_000;
            while (replayed.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(replayed.contains(topic + ":auto"), "consumer should replay pending record; saw " + replayed);
            consumer.stop();
            assertFalse(consumer.isRunning());
            consumer.close();
            assertTrue(consumer.isClosed());
            consumer.close(); // idempotent
            assertThrows(IllegalStateException.class, () -> consumer.subscribe(topic, null));
        } finally {
            client.getKeys().deleteByPattern("dlq*" + topic + "*");
            client.shutdown();
        }
    }
}
