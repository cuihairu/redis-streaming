package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DlqConsumerAdapter
 */
class DlqConsumerAdapterTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    @Test
    void testConstructorWithValidParameters() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        assertNotNull(adapter);
        assertFalse(adapter.isRunning());
        assertFalse(adapter.isClosed());
    }

    @Test
    void testConstructorWithMqOptions() {
        MockitoAnnotations.openMocks(this);
        MqOptions options = MqOptions.builder()
                .defaultDlqGroup("custom-dlq-group")
                .streamKeyPrefix("custom:stream")
                .build();

        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", options);

        assertNotNull(adapter);
    }

    @Test
    void testConstructorWithNullOptions() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        assertNotNull(adapter);
        // Should use defaults: dlq-group and stream:topic
    }

    @Test
    void testSubscribeWithoutGroup() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertDoesNotThrow(() -> adapter.subscribe("test-topic", handler));
    }

    @Test
    void testSubscribeWithGroup() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertDoesNotThrow(() -> adapter.subscribe("test-topic", "custom-group", handler));
    }

    @Test
    void testUnsubscribe() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        // Unsubscribe is a no-op as per implementation
        assertDoesNotThrow(() -> adapter.unsubscribe("test-topic"));
    }

    @Test
    void testStart() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        assertFalse(adapter.isRunning());
        adapter.start();
        assertTrue(adapter.isRunning());
    }

    @Test
    void testStop() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        adapter.start();
        assertTrue(adapter.isRunning());

        adapter.stop();
        assertFalse(adapter.isRunning());
    }

    @Test
    void testClose() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        assertFalse(adapter.isClosed());
        adapter.close();
        assertTrue(adapter.isClosed());
    }

    @Test
    void testStartStopIsIdempotent() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        adapter.start();
        adapter.start(); // Should not cause issues
        assertTrue(adapter.isRunning());

        adapter.stop();
        adapter.stop(); // Should not cause issues
        assertFalse(adapter.isRunning());
    }

    @Test
    void testCloseIsIdempotent() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        adapter.close();
        adapter.close(); // Should not cause issues
        assertTrue(adapter.isClosed());
    }

    @Test
    void testToResultWithSuccess() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        // The toResult method is private, but we can test indirectly through subscribe
        assertDoesNotThrow(() -> adapter.subscribe("test-topic", handler));
    }

    @Test
    void testToResultWithRetry() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.RETRY;

        assertDoesNotThrow(() -> adapter.subscribe("test-topic", handler));
    }

    @Test
    void testToResultWithFail() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.FAIL;

        assertDoesNotThrow(() -> adapter.subscribe("test-topic", handler));
    }

    @Test
    void testToResultWithDeadLetter() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.DEAD_LETTER;

        assertDoesNotThrow(() -> adapter.subscribe("test-topic", handler));
    }

    @Test
    void testSubscribeWithMultipleTopics() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertDoesNotThrow(() -> {
            adapter.subscribe("topic1", handler);
            adapter.subscribe("topic2", handler);
            adapter.subscribe("topic3", handler);
        });
    }

    @Test
    void testLifecycleOrder() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        // Test proper lifecycle order
        assertFalse(adapter.isRunning());
        assertFalse(adapter.isClosed());

        adapter.start();
        assertTrue(adapter.isRunning());
        assertFalse(adapter.isClosed());

        adapter.stop();
        assertFalse(adapter.isRunning());
        assertFalse(adapter.isClosed());

        adapter.close();
        assertFalse(adapter.isRunning());
        assertTrue(adapter.isClosed());
    }
}
