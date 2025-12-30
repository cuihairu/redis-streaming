package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerRecord;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DefaultBroker
 */
@Tag("integration")
class DefaultBrokerIntegrationTest {

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }

    @Test
    void testProduceReturnsMessageId() {
        String topic = "broker-produce-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(1).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("test-payload");

            String messageId = broker.produce(message);

            assertNotNull(messageId);
            assertTrue(messageId.contains("-"));
            assertEquals(messageId, message.getId());

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testProduceWithNullMessage() {
        String topic = "broker-null-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(1).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            String messageId = broker.produce(null);

            assertNull(messageId);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testProduceWithMultiplePartitions() {
        String topic = "broker-multi-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(4).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            // Produce messages with different keys
            for (int i = 0; i < 10; i++) {
                Message message = new Message();
                message.setTopic(topic);
                message.setKey("key-" + i);
                message.setPayload("payload-" + i);

                String messageId = broker.produce(message);
                assertNotNull(messageId);
            }

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testReadGroupCreatesMessages() {
        String topic = "broker-read-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "test-group";
        String consumer = "test-consumer";
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(1).consumerPollTimeoutMs(50).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            // Produce a message
            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("test-payload");

            String producedId = broker.produce(message);
            assertNotNull(producedId);

            // Read from group
            List<BrokerRecord> records = broker.readGroup(topic, group, consumer, 0, 10, 100);

            assertNotNull(records);
            assertEquals(1, records.size());
            assertEquals(producedId, records.get(0).getId());

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testReadGroupWithNoMessagesReturnsEmpty() {
        String topic = "broker-empty-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "test-group";
        String consumer = "test-consumer";
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(1).consumerPollTimeoutMs(50).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            // Read from group when no messages exist
            List<BrokerRecord> records = broker.readGroup(topic, group, consumer, 0, 10, 100);

            assertNotNull(records);
            assertTrue(records.isEmpty());

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testReadGroupWithTimeout() {
        String topic = "broker-timeout-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "test-group";
        String consumer = "test-consumer";
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(1).consumerPollTimeoutMs(50).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            // Read with short timeout when no messages exist
            List<BrokerRecord> records = broker.readGroup(topic, group, consumer, 0, 10, 50);

            assertNotNull(records);
            assertTrue(records.isEmpty());

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAckWithImmediatePolicy() {
        String topic = "broker-ack-immediate-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "test-group";
        String consumer = "test-consumer";
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder()
                    .defaultPartitionCount(1)
                    .consumerPollTimeoutMs(50)
                    .ackDeletePolicy("immediate")
                    .build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            // Produce and read message
            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("test-payload");

            String producedId = broker.produce(message);
            List<BrokerRecord> records = broker.readGroup(topic, group, consumer, 0, 10, 100);

            assertEquals(1, records.size());

            // Ack should not throw
            assertDoesNotThrow(() -> broker.ack(topic, group, 0, records.get(0).getId()));

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAckWithNonePolicy() {
        String topic = "broker-ack-none-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "test-group";
        String consumer = "test-consumer";
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder()
                    .defaultPartitionCount(1)
                    .consumerPollTimeoutMs(50)
                    .ackDeletePolicy("none")
                    .build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            // Produce and read message
            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("test-payload");

            String producedId = broker.produce(message);
            List<BrokerRecord> records = broker.readGroup(topic, group, consumer, 0, 10, 100);

            assertEquals(1, records.size());

            // Ack should not throw
            assertDoesNotThrow(() -> broker.ack(topic, group, 0, records.get(0).getId()));

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAckWithAllGroupsAckPolicy() {
        String topic = "broker-ack-all-" + UUID.randomUUID().toString().substring(0, 8);
        String group1 = "test-group-1";
        String group2 = "test-group-2";
        String consumer = "test-consumer";
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder()
                    .defaultPartitionCount(1)
                    .consumerPollTimeoutMs(50)
                    .ackDeletePolicy("all-groups-ack")
                    .acksetTtlSec(60)
                    .build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            // Produce and read message by both groups
            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("test-payload");

            String producedId = broker.produce(message);
            List<BrokerRecord> records1 = broker.readGroup(topic, group1, consumer, 0, 10, 100);
            List<BrokerRecord> records2 = broker.readGroup(topic, group2, consumer, 0, 10, 100);

            assertEquals(1, records1.size());
            assertEquals(1, records2.size());

            // First ack
            assertDoesNotThrow(() -> broker.ack(topic, group1, 0, records1.get(0).getId()));

            // Second ack should delete the message
            assertDoesNotThrow(() -> broker.ack(topic, group2, 0, records2.get(0).getId()));

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testProduceWithComplexHeaders() {
        String topic = "broker-headers-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(1).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("test-payload");
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("header1", "value1");
            headers.put("header2", "123");
            message.setHeaders(headers);

            String messageId = broker.produce(message);

            assertNotNull(messageId);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testProduceWithNullTopic() {
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(1).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            Message message = new Message();
            message.setTopic(null);
            message.setKey("test-key");
            message.setPayload("test-payload");

            // Should handle null topic gracefully or throw expected exception
            assertDoesNotThrow(() -> broker.produce(message));

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testProduceWithMapPayload() {
        String topic = "broker-map-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(1).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("field1", "value1");
            payload.put("field2", 456);
            message.setPayload(payload);

            String messageId = broker.produce(message);

            assertNotNull(messageId);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testReadMultipleMessages() {
        String topic = "broker-multi-msg-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "test-group";
        String consumer = "test-consumer";
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(1).consumerPollTimeoutMs(50).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            // Produce multiple messages
            for (int i = 0; i < 5; i++) {
                Message message = new Message();
                message.setTopic(topic);
                message.setKey("key-" + i);
                message.setPayload("payload-" + i);
                broker.produce(message);
            }

            // Read all messages
            List<BrokerRecord> records = broker.readGroup(topic, group, consumer, 0, 10, 100);

            assertEquals(5, records.size());

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testReadWithCountLimit() {
        String topic = "broker-count-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "test-group";
        String consumer = "test-consumer";
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(1).consumerPollTimeoutMs(50).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            // Produce multiple messages
            for (int i = 0; i < 10; i++) {
                Message message = new Message();
                message.setTopic(topic);
                message.setKey("key-" + i);
                message.setPayload("payload-" + i);
                broker.produce(message);
            }

            // Read with count limit
            List<BrokerRecord> records = broker.readGroup(topic, group, consumer, 0, 3, 100);

            assertTrue(records.size() <= 3);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testProduceAndReadFromDifferentPartition() {
        String topic = "broker-partition-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "test-group";
        String consumer = "test-consumer";
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(4).consumerPollTimeoutMs(50).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            // Produce message
            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("test-payload");

            String producedId = broker.produce(message);
            assertNotNull(producedId);

            // Try to read from different partitions (only one should have messages)
            int totalMessages = 0;
            for (int pid = 0; pid < 4; pid++) {
                List<BrokerRecord> records = broker.readGroup(topic, group, consumer + "-" + pid, pid, 10, 100);
                totalMessages += records.size();
            }

            assertEquals(1, totalMessages);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testAckWithInvalidMessageId() {
        String topic = "broker-ack-invalid-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "test-group";
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(1).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            // Ack with invalid message ID should not throw (best-effort)
            assertDoesNotThrow(() -> broker.ack(topic, group, 0, "invalid-id"));

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testProduceWithEmptyPayload() {
        String topic = "broker-empty-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(1).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("test-key");
            message.setPayload("");

            String messageId = broker.produce(message);

            assertNotNull(messageId);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testProduceWithNullKey() {
        String topic = "broker-null-key-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions options = MqOptions.builder().defaultPartitionCount(1).build();
            BrokerPersistence persistence = new RedisBrokerPersistence(client, options);
            HashBrokerRouter router = new HashBrokerRouter();
            Broker broker = new DefaultBroker(client, options, router, persistence);

            Message message = new Message();
            message.setTopic(topic);
            message.setKey(null);
            message.setPayload("test-payload");

            String messageId = broker.produce(message);

            assertNotNull(messageId);

        } finally {
            client.shutdown();
        }
    }
}
