package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedisMessageConsumer
 */
class RedisMessageConsumerTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    @Mock
    private TopicPartitionRegistry mockPartitionRegistry;

    private RedisMessageConsumer createConsumer(MqOptions options) {
        MockitoAnnotations.openMocks(this);
        when(mockPartitionRegistry.getPartitionCount(anyString())).thenReturn(1);
        return new RedisMessageConsumer(mockRedissonClient, "test-consumer",
                mockPartitionRegistry, options != null ? options : MqOptions.builder().build());
    }

    @Test
    void testConstructorWithValidParameters() {
        RedisMessageConsumer consumer = createConsumer(null);

        assertNotNull(consumer);
        assertFalse(consumer.isRunning());
        assertFalse(consumer.isClosed());
    }

    @Test
    void testConstructorWithCustomOptions() {
        MqOptions options = MqOptions.builder()
                .workerThreads(4)
                .schedulerThreads(1)
                .consumerBatchCount(20)
                .leaseTtlSeconds(30)
                .build();

        RedisMessageConsumer consumer = createConsumer(options);

        assertNotNull(consumer);
    }

    @Test
    void testConstructorWithNullOptions() {
        RedisMessageConsumer consumer = new RedisMessageConsumer(mockRedissonClient, "test-consumer",
                mockPartitionRegistry, null);

        assertNotNull(consumer);
        // Should use default options
    }

    @Test
    void testConstructorWithBroker() {
        MockitoAnnotations.openMocks(this);
        io.github.cuihairu.redis.streaming.mq.broker.Broker mockBroker = mock(io.github.cuihairu.redis.streaming.mq.broker.Broker.class);

        RedisMessageConsumer consumer = new RedisMessageConsumer(mockRedissonClient, "test-consumer",
                mockPartitionRegistry, MqOptions.builder().build(), mockBroker);

        assertNotNull(consumer);
        assertFalse(consumer.isRunning());
        assertFalse(consumer.isClosed());
    }

    @Test
    void testSubscribeWithoutGroup() {
        RedisMessageConsumer consumer = createConsumer(null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertDoesNotThrow(() -> consumer.subscribe("test-topic", handler));
    }

    @Test
    void testSubscribeWithGroup() {
        RedisMessageConsumer consumer = createConsumer(null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertDoesNotThrow(() -> consumer.subscribe("test-topic", "custom-group", handler));
    }

    @Test
    void testSubscribeWithSubscriptionOptions() {
        RedisMessageConsumer consumer = createConsumer(null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;
        io.github.cuihairu.redis.streaming.mq.SubscriptionOptions opts =
                io.github.cuihairu.redis.streaming.mq.SubscriptionOptions.builder()
                        .batchCount(50)
                        .pollTimeoutMs(2000)
                        .build();

        assertDoesNotThrow(() -> consumer.subscribe("test-topic", "custom-group", handler, opts));
    }

    @Test
    void testSubscribeWhenClosedThrowsException() {
        RedisMessageConsumer consumer = createConsumer(null);
        consumer.close();

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertThrows(IllegalStateException.class, () ->
            consumer.subscribe("test-topic", handler)
        );
    }

    @Test
    void testUnsubscribe() {
        RedisMessageConsumer consumer = createConsumer(null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;
        consumer.subscribe("test-topic", handler);

        assertDoesNotThrow(() -> consumer.unsubscribe("test-topic"));
    }

    @Test
    void testUnsubscribeNonExistentTopic() {
        RedisMessageConsumer consumer = createConsumer(null);

        // Should not throw exception
        assertDoesNotThrow(() -> consumer.unsubscribe("non-existent-topic"));
    }

    @Test
    void testStart() {
        RedisMessageConsumer consumer = createConsumer(null);

        assertFalse(consumer.isRunning());
        consumer.start();
        assertTrue(consumer.isRunning());
    }

    @Test
    void testStop() {
        RedisMessageConsumer consumer = createConsumer(null);
        consumer.start();

        assertTrue(consumer.isRunning());
        consumer.stop();
        assertFalse(consumer.isRunning());
    }

    @Test
    void testStopWhenNotRunning() {
        RedisMessageConsumer consumer = createConsumer(null);

        assertFalse(consumer.isRunning());
        // Should not throw exception
        assertDoesNotThrow(() -> consumer.stop());
    }

    @Test
    void testClose() {
        RedisMessageConsumer consumer = createConsumer(null);

        assertFalse(consumer.isClosed());
        consumer.close();
        assertTrue(consumer.isClosed());
    }

    @Test
    void testCloseIsIdempotent() {
        RedisMessageConsumer consumer = createConsumer(null);

        consumer.close();
        consumer.close(); // Should not cause issues
        assertTrue(consumer.isClosed());
    }

    @Test
    void testStartAndClose() {
        RedisMessageConsumer consumer = createConsumer(null);

        consumer.start();
        assertTrue(consumer.isRunning());

        consumer.close();
        assertTrue(consumer.isClosed());
        // After close, running should be false
        assertFalse(consumer.isRunning());
    }

    @Test
    void testStartIsIdempotent() {
        RedisMessageConsumer consumer = createConsumer(null);

        consumer.start();
        consumer.start(); // Should not cause issues
        assertTrue(consumer.isRunning());
    }

    @Test
    void testSubscribeToMultipleTopics() {
        RedisMessageConsumer consumer = createConsumer(null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertDoesNotThrow(() -> {
            consumer.subscribe("topic1", handler);
            consumer.subscribe("topic2", handler);
            consumer.subscribe("topic3", handler);
        });
    }

    @Test
    void testUnsubscribeStopsWorkers() {
        RedisMessageConsumer consumer = createConsumer(null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;
        consumer.subscribe("test-topic", handler);

        // Start would create workers, but we're testing the unsubscribe mechanism
        assertDoesNotThrow(() -> consumer.unsubscribe("test-topic"));
    }

    @Test
    void testConsumerWithMultiplePartitions() {
        // Need to reset mock and set expectation before creating consumer
        MockitoAnnotations.openMocks(this);
        when(mockPartitionRegistry.getPartitionCount("test-topic")).thenReturn(5);

        RedisMessageConsumer consumer = new RedisMessageConsumer(mockRedissonClient, "test-consumer",
                mockPartitionRegistry, MqOptions.builder().build());

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertDoesNotThrow(() -> consumer.subscribe("test-topic", handler));
    }

    @Test
    void testSubscribeAfterCloseThrowsException() {
        RedisMessageConsumer consumer = createConsumer(null);
        consumer.close();

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertThrows(IllegalStateException.class, () ->
            consumer.subscribe("test-topic", "group", handler)
        );
    }

    @Test
    void testConsumerWithCustomThreads() {
        MqOptions options = MqOptions.builder()
                .workerThreads(16)
                .schedulerThreads(4)
                .rebalanceIntervalSec(10)
                .renewIntervalSec(5)
                .build();

        RedisMessageConsumer consumer = createConsumer(options);

        assertNotNull(consumer);
        assertDoesNotThrow(() -> consumer.start());
        assertDoesNotThrow(() -> consumer.close());
    }

    @Test
    void testConsumerWithNullPartitionRegistry() {
        // The constructor accepts null partitionRegistry but will throw NPE when used
        // Let's just verify it doesn't throw at construction
        assertDoesNotThrow(() ->
            new RedisMessageConsumer(mockRedissonClient, "test-consumer", null, MqOptions.builder().build())
        );
    }

    @Test
    void testConsumerWithNullRedissonClient() {
        // The constructor accepts null redissonClient
        assertDoesNotThrow(() ->
            new RedisMessageConsumer(null, "test-consumer", mockPartitionRegistry, MqOptions.builder().build())
        );
    }

    @Test
    void testConsumerWithNullConsumerName() {
        // The constructor accepts null consumerName
        assertDoesNotThrow(() ->
            new RedisMessageConsumer(mockRedissonClient, null, mockPartitionRegistry, MqOptions.builder().build())
        );
    }

    @Test
    void testLifecycleTransitions() {
        RedisMessageConsumer consumer = createConsumer(null);

        // Initial state
        assertFalse(consumer.isRunning());
        assertFalse(consumer.isClosed());

        // Start
        consumer.start();
        assertTrue(consumer.isRunning());
        assertFalse(consumer.isClosed());

        // Stop
        consumer.stop();
        assertFalse(consumer.isRunning());
        assertFalse(consumer.isClosed());

        // Restart
        consumer.start();
        assertTrue(consumer.isRunning());
        assertFalse(consumer.isClosed());

        // Close
        consumer.close();
        assertFalse(consumer.isRunning());
        assertTrue(consumer.isClosed());
    }

    @Test
    void testConsumerWithRetryOptions() {
        MqOptions options = MqOptions.builder()
                .retryMaxAttempts(10)
                .retryBaseBackoffMs(500)
                .retryMaxBackoffMs(30000)
                .build();

        RedisMessageConsumer consumer = createConsumer(options);

        assertNotNull(consumer);
    }

    @Test
    void testConsumerWithLeaseOptions() {
        MqOptions options = MqOptions.builder()
                .leaseTtlSeconds(60)
                .rebalanceIntervalSec(10)
                .renewIntervalSec(5)
                .build();

        RedisMessageConsumer consumer = createConsumer(options);

        assertNotNull(consumer);
    }

    @Test
    void testConsumerWithConsumerOptions() {
        MqOptions options = MqOptions.builder()
                .consumerBatchCount(100)
                .consumerPollTimeoutMs(5000)
                .claimIdleMs(600000)
                .claimBatchSize(100)
                .build();

        RedisMessageConsumer consumer = createConsumer(options);

        assertNotNull(consumer);
    }

    @Test
    void testConsumerWithRetryMoverOptions() {
        MqOptions options = MqOptions.builder()
                .retryMoverBatch(200)
                .retryMoverIntervalSec(2)
                .retryLockWaitMs(200)
                .retryLockLeaseMs(1000)
                .pendingScanIntervalSec(60)
                .build();

        RedisMessageConsumer consumer = createConsumer(options);

        assertNotNull(consumer);
    }

    @Test
    void testParseStreamIdWithValidId() throws Exception {
        // Use reflection to test private method
        RedisMessageConsumer consumer = createConsumer(null);
        java.lang.reflect.Method method = RedisMessageConsumer.class.getDeclaredMethod("parseStreamId", String.class);
        method.setAccessible(true);

        org.redisson.api.StreamMessageId result = (org.redisson.api.StreamMessageId) method.invoke(consumer, "1234567890-0");

        assertNotNull(result);
    }

    @Test
    void testParseStreamIdWithNull() throws Exception {
        RedisMessageConsumer consumer = createConsumer(null);
        java.lang.reflect.Method method = RedisMessageConsumer.class.getDeclaredMethod("parseStreamId", String.class);
        method.setAccessible(true);

        org.redisson.api.StreamMessageId result = (org.redisson.api.StreamMessageId) method.invoke(consumer, (String) null);

        assertEquals(org.redisson.api.StreamMessageId.MIN, result);
    }

    @Test
    void testCompareStreamId() throws Exception {
        RedisMessageConsumer consumer = createConsumer(null);
        java.lang.reflect.Method method = RedisMessageConsumer.class.getDeclaredMethod("compareStreamId", String.class, String.class);
        method.setAccessible(true);

        int result = (int) method.invoke(consumer, "1234567890-0", "1234567890-1");

        assertTrue(result < 0); // First ID is smaller
    }

    @Test
    void testObjectToJson() throws Exception {
        RedisMessageConsumer consumer = createConsumer(null);
        java.lang.reflect.Method method = RedisMessageConsumer.class.getDeclaredMethod("objectToJson", Object.class);
        method.setAccessible(true);

        String result = (String) method.invoke(consumer, "test-payload");

        assertEquals("\"test-payload\"", result);
    }

    @Test
    void testJsonToObject() throws Exception {
        RedisMessageConsumer consumer = createConsumer(null);
        java.lang.reflect.Method method = RedisMessageConsumer.class.getDeclaredMethod("jsonToObject", String.class, Class.class);
        method.setAccessible(true);

        String result = (String) method.invoke(consumer, "\"test-value\"", String.class);

        assertEquals("test-value", result);
    }

    @Test
    void testPartitionKeyEqualsAndHashCode() throws Exception {
        // Test inner class PartitionKey
        RedisMessageConsumer consumer = createConsumer(null);
        Class<?> partitionKeyClass = Class.forName("io.github.cuihairu.redis.streaming.mq.impl.RedisMessageConsumer$PartitionKey");
        java.lang.reflect.Constructor<?> constructor = partitionKeyClass.getDeclaredConstructor(String.class, String.class, int.class);
        constructor.setAccessible(true);

        Object key1 = constructor.newInstance("topic", "group", 0);
        Object key2 = constructor.newInstance("topic", "group", 0);
        Object key3 = constructor.newInstance("topic", "group", 1);

        assertEquals(key1, key2);
        assertNotEquals(key1, key3);
        assertEquals(key1.hashCode(), key2.hashCode());
    }

    @Test
    void testPartitionWorkerStop() throws Exception {
        // Test inner class PartitionWorker
        Class<?> partitionWorkerClass = Class.forName("io.github.cuihairu.redis.streaming.mq.impl.RedisMessageConsumer$PartitionWorker");
        java.lang.reflect.Constructor<?> constructor = partitionWorkerClass.getDeclaredConstructor(String.class, String.class, int.class, MessageHandler.class);
        constructor.setAccessible(true);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;
        Object worker = constructor.newInstance("topic", "group", 0, handler);

        java.lang.reflect.Method stopMethod = partitionWorkerClass.getDeclaredMethod("stop");
        stopMethod.setAccessible(true);

        assertDoesNotThrow(() -> stopMethod.invoke(worker));
    }
}
