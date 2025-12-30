package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for RedisBrokerPersistence
 */
@Tag("integration")
class RedisBrokerPersistenceIntegrationTest {

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }

    @Test
    void testAppendReturnsMessageId() {
        String topic = "persist-append-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("test-payload");

            String messageId = persistence.append(topic, 0, message);

            assertNotNull(messageId);
            assertTrue(messageId.contains("-"));

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendWithNullMessage() {
        String topic = "persist-null-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            String messageId = persistence.append(topic, 0, null);

            assertNull(messageId);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendCreatesStreamEntry() {
        String topic = "persist-entry-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("test-payload");

            String messageId = persistence.append(topic, 0, message);

            // Verify the entry exists in the stream
            String streamKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, 0);
            RStream<String, Object> stream = client.getStream(streamKey, StringCodec.INSTANCE);

            long size = stream.size();
            assertTrue(size > 0);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendWithRetentionMaxLen() {
        String topic = "persist-maxlen-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().retentionMaxLenPerPartition(5).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            // Append more messages than maxLen
            for (int i = 0; i < 10; i++) {
                Message message = new Message();
                message.setTopic(topic);
                message.setKey("key-" + i);
                message.setPayload("payload-" + i);
                String messageId = persistence.append(topic, 0, message);
                assertNotNull(messageId);
            }

            // Verify stream size is approximately at most maxLen
            String streamKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, 0);
            RStream<String, Object> stream = client.getStream(streamKey, StringCodec.INSTANCE);

            long size = stream.size();
            assertTrue(size <= 6, "Stream size should be at most maxLen + 1, got: " + size);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendToMultiplePartitions() {
        String topic = "persist-multi-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            // Append to different partitions
            for (int pid = 0; pid < 3; pid++) {
                Message message = new Message();
                message.setTopic(topic);
                message.setKey("key-" + pid);
                message.setPayload("payload-" + pid);

                String messageId = persistence.append(topic, pid, message);
                assertNotNull(messageId);

                // Verify entry exists in the correct partition
                String streamKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, pid);
                RStream<String, Object> stream = client.getStream(streamKey, StringCodec.INSTANCE);
                assertEquals(1, stream.size());
            }

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendWithComplexPayload() {
        String topic = "persist-complex-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");

            java.util.Map<String, Object> complexPayload = new java.util.HashMap<>();
            complexPayload.put("string", "value");
            complexPayload.put("number", 123);
            complexPayload.put("bool", true);
            message.setPayload(complexPayload);

            String messageId = persistence.append(topic, 0, message);

            assertNotNull(messageId);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendWithHeaders() {
        String topic = "persist-headers-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("test-payload");
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("header1", "value1");
            headers.put("header2", "456");
            message.setHeaders(headers);

            String messageId = persistence.append(topic, 0, message);

            assertNotNull(messageId);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendWithEmptyPayload() {
        String topic = "persist-empty-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("");

            String messageId = persistence.append(topic, 0, message);

            assertNotNull(messageId);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendWithNullOptions() {
        String topic = "persist-null-opt-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            BrokerPersistence persistence = new RedisBrokerPersistence(client, null);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("test-payload");

            String messageId = persistence.append(topic, 0, message);

            assertNotNull(messageId);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendWithZeroMaxLen() {
        String topic = "persist-zero-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().retentionMaxLenPerPartition(0).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("test-payload");

            String messageId = persistence.append(topic, 0, message);

            assertNotNull(messageId);

            // With zero maxLen, retention should be disabled
            String streamKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, 0);
            RStream<String, Object> stream = client.getStream(streamKey, StringCodec.INSTANCE);
            assertEquals(1, stream.size());

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendWithNegativeMaxLen() {
        String topic = "persist-neg-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().retentionMaxLenPerPartition(-1).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("test-payload");

            String messageId = persistence.append(topic, 0, message);

            assertNotNull(messageId);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendPreservesMessageContent() {
        String topic = "persist-content-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("test-payload");

            String messageId = persistence.append(topic, 0, message);

            // Read back the message
            String streamKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, 0);
            RStream<String, Object> stream = client.getStream(streamKey, StringCodec.INSTANCE);

            java.util.Map<StreamMessageId, java.util.Map<String, Object>> entries =
                    stream.range(1, StreamMessageId.MIN, StreamMessageId.MAX);

            assertEquals(1, entries.size());
            java.util.Map<String, Object> entry = entries.values().iterator().next();

            // Verify payload is stored
            assertTrue(entry.containsKey("payload") || entry.containsKey("p"));

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendWithNullKey() {
        String topic = "persist-null-key-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey(null);
            message.setPayload("test-payload");

            String messageId = persistence.append(topic, 0, message);

            assertNotNull(messageId);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendWithLargePayload() {
        String topic = "persist-large-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            // Create a large payload
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                sb.append("x");
            }

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload(sb.toString());

            String messageId = persistence.append(topic, 0, message);

            assertNotNull(messageId);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendMultipleMessagesSamePartition() {
        String topic = "persist-multi-same-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            // Append multiple messages to same partition
            for (int i = 0; i < 5; i++) {
                Message message = new Message();
                message.setTopic(topic);
                message.setKey("key-" + i);
                message.setPayload("payload-" + i);

                String messageId = persistence.append(topic, 0, message);
                assertNotNull(messageId);
            }

            // Verify all messages are stored
            String streamKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, 0);
            RStream<String, Object> stream = client.getStream(streamKey, StringCodec.INSTANCE);

            assertEquals(5, stream.size());

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAppendWithRetentionMaxLenTrimming() {
        String topic = "persist-trim-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().retentionMaxLenPerPartition(3).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);

            // Append 10 messages
            for (int i = 0; i < 10; i++) {
                Message message = new Message();
                message.setTopic(topic);
                message.setKey("key-" + i);
                message.setPayload("payload-" + i);
                persistence.append(topic, 0, message);
            }

            // Verify trimming occurred
            String streamKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, 0);
            RStream<String, Object> stream = client.getStream(streamKey, StringCodec.INSTANCE);

            long size = stream.size();
            assertTrue(size <= 4, "Stream size should be at most maxLen + 1, got: " + size);

        } finally {
            client.shutdown();
        }
    }
}
