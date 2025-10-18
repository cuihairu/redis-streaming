package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class MissingPayloadDlqIntegrationTest {

    @Test
    void missingPayloadGoesToDlqAndAcked() throws Exception {
        String topic = "misspl-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder()
                    .defaultPartitionCount(1)
                    .consumerPollTimeoutMs(100)
                    .ackDeletePolicy("none")
                    .build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageConsumer consumer = factory.createConsumer("c-misspl");
            // Subscribe (handler should not be called because parse will trigger missing payload path)
            consumer.subscribe(topic, "g", m -> MessageHandleResult.SUCCESS);
            consumer.start();

            // Craft a stream entry that points to a non-existent payload hash
            String streamKey = StreamKeys.partitionStream(topic, 0);
            RStream<String, Object> s = client.getStream(streamKey, org.redisson.client.codec.StringCodec.INSTANCE);
            Map<String,Object> data = new HashMap<>();
            data.put("payload", "");
            data.put("timestamp", Instant.now().toString());
            data.put("retryCount", "0");
            data.put("maxRetries", "1");
            data.put("topic", topic);
            data.put("partitionId", "0");
            // headers JSON indicating hash storage with a fake ref
            String headersJson = "{\"x-payload-storage-type\":\"hash\",\"x-payload-hash-ref\":\"streaming:mq:payload:" + topic + ":p:0:missing\",\"x-payload-original-size\":\"80000\"}";
            data.put("headers", headersJson);
            s.add(StreamAddArgs.entries(data));

            // Wait for DLQ to receive the missing-payload entry
            DeadLetterQueueManager dlq = new DeadLetterQueueManager(client);
            boolean ok = waitUntil(() -> dlq.getDeadLetterQueueSize(topic) > 0, 8000);
            assertTrue(ok, "DLQ should receive missing-payload entry");

            // Verify DLQ headers contain payload-missing markers (best effort)
            Map<StreamMessageId, Map<String,Object>> msgs = dlq.getDeadLetterMessages(topic, 10);
            assertNotNull(msgs);
            assertFalse(msgs.isEmpty());

            consumer.stop(); consumer.close();
        } finally { client.shutdown(); }
    }

    private boolean waitUntil(java.util.concurrent.Callable<Boolean> cond, long timeoutMs) throws Exception {
        long dl = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < dl) {
            if (Boolean.TRUE.equals(cond.call())) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

