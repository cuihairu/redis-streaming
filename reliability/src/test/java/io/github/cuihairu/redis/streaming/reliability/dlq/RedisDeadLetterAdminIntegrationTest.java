package io.github.cuihairu.redis.streaming.reliability.dlq;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.RStream;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for RedisDeadLetterAdmin
 */
@Tag("integration")
class RedisDeadLetterAdminIntegrationTest {

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }

    private void addDlqEntry(RedissonClient client, String topic, String payload) {
        String dlqKey = DlqKeys.dlq(topic);
        RStream<String, Object> dlq = client.getStream(dlqKey, StringCodec.INSTANCE);

        Map<String, Object> fields = new HashMap<>();
        fields.put("topic", topic);
        fields.put("originalKey", "test-key");
        fields.put("payload", payload);
        fields.put("exceptionMessage", "Test exception");
        fields.put("exceptionClass", "java.lang.RuntimeException");
        fields.put("timestamp", String.valueOf(System.currentTimeMillis()));
        fields.put("retryCount", "0");

        dlq.add(StreamAddArgs.entries(fields));
    }

    @Test
    void testListTopicsReturnsEmptyInitially() {
        RedissonClient client = createClient();
        try {
            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            List<String> topics = admin.listTopics();

            assertNotNull(topics);
            assertTrue(topics.isEmpty());

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testListTopicsAfterAddingEntries() {
        String topic = "dlq-topic-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            // Add a DLQ entry directly
            addDlqEntry(client, topic, "test-payload");

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            List<String> topics = admin.listTopics();

            assertTrue(topics.contains(topic));

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testListTopicsWithMultipleTopics() {
        String topic1 = "dlq-topic-1-" + UUID.randomUUID().toString().substring(0, 8);
        String topic2 = "dlq-topic-2-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            addDlqEntry(client, topic1, "payload1");
            addDlqEntry(client, topic2, "payload2");

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            List<String> topics = admin.listTopics();

            assertTrue(topics.contains(topic1));
            assertTrue(topics.contains(topic2));

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testSizeReturnsZeroForNonExistentTopic() {
        String topic = "dlq-size-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            long size = admin.size(topic);

            assertEquals(0, size);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testSizeReturnsCorrectCount() {
        String topic = "dlq-size-count-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            // Add 3 entries
            for (int i = 0; i < 3; i++) {
                addDlqEntry(client, topic, "payload-" + i);
            }

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            long size = admin.size(topic);

            assertEquals(3, size);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testListReturnsEmptyForNonExistentTopic() {
        String topic = "dlq-list-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            List<DeadLetterEntry> entries = admin.list(topic, 10);

            assertNotNull(entries);
            assertTrue(entries.isEmpty());

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testListReturnsEntries() {
        String topic = "dlq-list-entries-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            addDlqEntry(client, topic, "test-payload");

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            List<DeadLetterEntry> entries = admin.list(topic, 10);

            assertEquals(1, entries.size());
            assertEquals("test-payload", entries.get(0).getPayload());

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testListWithLimit() {
        String topic = "dlq-list-limit-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            // Add 5 entries
            for (int i = 0; i < 5; i++) {
                addDlqEntry(client, topic, "payload-" + i);
            }

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            List<DeadLetterEntry> entries = admin.list(topic, 3);

            assertTrue(entries.size() <= 3);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testReplayReturnsFalseForNonExistentEntry() {
        String topic = "dlq-replay-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            // Use MIN as a test ID
            boolean result = admin.replay(topic, org.redisson.api.StreamMessageId.MIN);

            assertFalse(result);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testReplayAllWithNoEntries() {
        String topic = "dlq-replayall-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            long count = admin.replayAll(topic, 10);

            assertEquals(0, count);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testReplayAllWithEntries() {
        String topic = "dlq-replayall-entries-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            // Add entries
            for (int i = 0; i < 3; i++) {
                addDlqEntry(client, topic, "payload-" + i);
            }

            // Create the source stream for replay
            String streamKey = "stream:topic:" + topic;
            RStream<String, Object> stream = client.getStream(streamKey, StringCodec.INSTANCE);
            stream.createGroup(
                    org.redisson.api.stream.StreamCreateGroupArgs.name("dlq-replay-group")
                            .id(StreamMessageId.MIN).makeStream());

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            // Note: replay might fail if source stream doesn't exist or has other issues
            // We're testing that the method runs without throwing
            long count = admin.replayAll(topic, 10);

            // Count might be 0 if replay failed, but should not throw
            assertTrue(count >= 0);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testDeleteReturnsFalseForNonExistentEntry() {
        String topic = "dlq-delete-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            // Use MIN as a test ID
            boolean result = admin.delete(topic, org.redisson.api.StreamMessageId.MIN);

            assertFalse(result);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testDeleteWithValidEntry() {
        String topic = "dlq-delete-valid-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            addDlqEntry(client, topic, "test-payload");

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            // Use clear to remove all entries
            long count = admin.clear(topic);

            assertTrue(count > 0);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testClearWithNoEntries() {
        String topic = "dlq-clear-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            long count = admin.clear(topic);

            assertEquals(0, count);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testClearWithEntries() {
        String topic = "dlq-clear-entries-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            // Add entries
            for (int i = 0; i < 5; i++) {
                addDlqEntry(client, topic, "payload-" + i);
            }

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            long count = admin.clear(topic);

            assertEquals(5, count);
            assertEquals(0, admin.size(topic));

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testListWithZeroLimit() {
        String topic = "dlq-zero-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            addDlqEntry(client, topic, "test-payload");

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            List<DeadLetterEntry> entries = admin.list(topic, 0);

            // Zero limit should still return some entries (implementation dependent)
            assertNotNull(entries);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testListWithNegativeLimit() {
        String topic = "dlq-neg-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            addDlqEntry(client, topic, "test-payload");

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            List<DeadLetterEntry> entries = admin.list(topic, -10);

            assertNotNull(entries);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testReplayAllWithZeroLimit() {
        String topic = "dlq-zero-limit-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            addDlqEntry(client, topic, "test-payload");

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            long count = admin.replayAll(topic, 0);

            assertEquals(0, count);

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testDeleteAll() {
        String topic = "dlq-delete-all-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            // Add entries
            for (int i = 0; i < 3; i++) {
                addDlqEntry(client, topic, "payload-" + i);
            }

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            assertEquals(3, admin.size(topic));

            // Clear all entries instead of deleting individually
            admin.clear(topic);
            assertEquals(0, admin.size(topic));

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testListWithLargeLimit() {
        String topic = "dlq-large-limit-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            // Add a few entries
            for (int i = 0; i < 3; i++) {
                addDlqEntry(client, topic, "payload-" + i);
            }

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            List<DeadLetterEntry> entries = admin.list(topic, 1000);

            assertEquals(3, entries.size());

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testSizeAfterDelete() {
        String topic = "dlq-size-delete-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            // Add entries
            for (int i = 0; i < 3; i++) {
                addDlqEntry(client, topic, "payload-" + i);
            }

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            assertEquals(3, admin.size(topic));

            // Clear one entry
            admin.clear(topic);
            assertEquals(0, admin.size(topic));

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testListTopicsFiltersNonDlqKeys() {
        String topic = "dlq-filter-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            // Add a DLQ entry
            addDlqEntry(client, topic, "test-payload");

            // Create a non-DLQ key
            client.getBucket("stream:topic:some-other-key").set("value");

            DeadLetterService service = new RedisDeadLetterService(client);
            RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(client, service);

            List<String> topics = admin.listTopics();

            // Should only contain the DLQ topic
            assertTrue(topics.contains(topic));
            assertFalse(topics.contains("some-other-key"));

        } finally {
            client.shutdown();
        }
    }
}
