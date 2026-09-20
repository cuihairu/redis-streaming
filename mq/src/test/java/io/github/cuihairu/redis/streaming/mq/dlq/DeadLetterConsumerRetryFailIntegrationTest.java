package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** RETRY/FAIL handler outcomes of the background DLQ consumer on real Redis. */
@Tag("integration")
class DeadLetterConsumerRetryFailIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    private static DeadLetterRecord record(String topic, String payload) {
        DeadLetterRecord r = new DeadLetterRecord();
        r.originalTopic = topic;
        r.payload = payload;
        r.retryCount = 1;
        r.maxRetries = 3;
        r.headers = new HashMap<>(java.util.Map.of("k", "v"));
        r.originalMessageId = "orig-" + payload;
        r.timestamp = Instant.now();
        return r;
    }

    @Test
    void retryReplaysAndFailDrops() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String topic = "dlq-rf-" + uid;
        RedisDeadLetterService service = new RedisDeadLetterService(redis, (t, p, pl, h, mr) -> false);
        StreamMessageId keep = service.send(record(topic, "retry-me"));
        service.send(record(topic, "fail-me"));
        CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();
        RedisDeadLetterConsumer consumer = new RedisDeadLetterConsumer(
                redis, "c-rf", "g-rf", (t, p, pl, h, mr) -> true);
        try {
            consumer.subscribe(topic, "g-rf", entry -> {
                String payload = String.valueOf(entry.getPayload());
                handled.add(payload);
                return payload.startsWith("retry")
                        ? DeadLetterConsumer.HandleResult.RETRY
                        : DeadLetterConsumer.HandleResult.FAIL;
            });
            consumer.start();
            long deadline = System.currentTimeMillis() + 20_000;
            while ((handled.size() < 2) && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            assertTrue(handled.contains("retry-me"), "handled=" + handled);
            assertTrue(handled.contains("fail-me"), "handled=" + handled);
            // RETRY+replay-success and FAIL both ack: the PEL must drain (entries stay in the
            // stream itself until retention trims it, so assert on pending, not size)
            deadline = System.currentTimeMillis() + 20_000;
            org.redisson.api.RStream<String, Object> dlq = redis.getStream(DlqKeys.dlq(topic));
            long pending = 99;
            while (pending > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(300);
                pending = dlq.listPending("g-rf", org.redisson.api.stream.StreamMessageId.MIN,
                        org.redisson.api.stream.StreamMessageId.MAX, 100).size();
            }
            assertEquals(0L, pending, "records should be acked after retry/fail handling");
        } finally {
            consumer.close();
            service.clear(topic);
            redis.getKeys().deleteByPattern(DlqKeys.dlq(topic));
            redis.shutdown();
        }
    }
}
