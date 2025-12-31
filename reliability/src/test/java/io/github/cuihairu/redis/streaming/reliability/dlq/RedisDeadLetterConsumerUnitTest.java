package io.github.cuihairu.redis.streaming.reliability.dlq;

import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisDeadLetterConsumerUnitTest {

    @Test
    void groupConsumptionAcksOnSuccess() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream dlqDefault = mock(RStream.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream dlqString = mock(RStream.class);

        String topic = "t";
        String group = "g";
        String key = DlqKeys.dlq(topic);

        when(redisson.getStream(eq(key))).thenReturn((RStream) dlqDefault);
        when(redisson.getStream(eq(key), eq(StringCodec.INSTANCE))).thenReturn((RStream) dlqString);

        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "orig");
        data.put("partitionId", 1);
        data.put("payload", "payload");
        data.put("timestamp", Instant.EPOCH.toString());
        data.put("retryCount", 0);
        data.put("maxRetries", 3);

        StreamMessageId id = new StreamMessageId(1, 0);
        Map<StreamMessageId, Map<String, Object>> batch = Map.of(id, data);

        when(((RStream) dlqDefault).readGroup(eq(group), eq("c1"), any(StreamReadGroupArgs.class)))
                .thenReturn(batch)
                .thenReturn(Collections.emptyMap());

        CountDownLatch latch = new CountDownLatch(1);
        RedisDeadLetterConsumer consumer = new RedisDeadLetterConsumer(redisson, "c1", group, null);
        try {
            consumer.subscribe(topic, group, entry -> {
                latch.countDown();
                consumer.stop();
                return DeadLetterConsumer.HandleResult.SUCCESS;
            });
            consumer.start();

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            verify((RStream) dlqDefault, timeout(2000).times(1)).ack(eq(group), eq(id));
        } finally {
            consumer.close();
        }
    }

    @Test
    void retryUsesReplayHandlerAndAcksOnlyOnSuccess() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream dlqDefault = mock(RStream.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream dlqString = mock(RStream.class);

        String topic = "t";
        String group = "g";
        String key = DlqKeys.dlq(topic);
        when(redisson.getStream(eq(key))).thenReturn((RStream) dlqDefault);
        when(redisson.getStream(eq(key), eq(StringCodec.INSTANCE))).thenReturn((RStream) dlqString);

        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "orig");
        data.put("partitionId", 2);
        data.put("payload", "payload");
        data.put("timestamp", Instant.EPOCH.toString());
        data.put("retryCount", 1);
        data.put("maxRetries", 7);
        data.put("headers", Map.of("x", "y"));

        StreamMessageId id = new StreamMessageId(2, 0);
        Map<StreamMessageId, Map<String, Object>> batch = Map.of(id, data);
        when(((RStream) dlqDefault).readGroup(eq(group), eq("c2"), any(StreamReadGroupArgs.class)))
                .thenReturn(batch)
                .thenReturn(Collections.emptyMap());

        ReplayHandler replay = mock(ReplayHandler.class);
        when(replay.publish(eq("orig"), eq(2), eq("payload"), any(), eq(7))).thenReturn(true);

        CountDownLatch latch = new CountDownLatch(1);
        RedisDeadLetterConsumer consumer = new RedisDeadLetterConsumer(redisson, "c2", group, replay);
        try {
            consumer.subscribe(topic, group, entry -> {
                latch.countDown();
                consumer.stop();
                return DeadLetterConsumer.HandleResult.RETRY;
            });
            consumer.start();

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            verify(replay, timeout(2000)).publish(eq("orig"), eq(2), eq("payload"), any(), eq(7));
            verify((RStream) dlqDefault, timeout(2000)).ack(eq(group), eq(id));
        } finally {
            consumer.close();
        }
    }

    @Test
    void subscribeFailsWhenClosed() {
        RedisDeadLetterConsumer consumer = new RedisDeadLetterConsumer(mock(RedissonClient.class), "c", "g");
        consumer.close();
        assertThrows(IllegalStateException.class, () -> consumer.subscribe("t", entry -> DeadLetterConsumer.HandleResult.SUCCESS));
    }
}
