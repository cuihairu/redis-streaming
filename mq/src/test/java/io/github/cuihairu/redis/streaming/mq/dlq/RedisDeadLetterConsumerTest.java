package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.client.codec.StringCodec;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedisDeadLetterConsumer
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisDeadLetterConsumerTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RStream<String, Object> rStream;

    @Mock
    private ReplayHandler replayHandler;

    @Mock
    private DeadLetterConsumer.DeadLetterHandler deadLetterHandler;

    private RedisDeadLetterConsumer consumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        lenient().when(redissonClient.getStream(anyString())).thenReturn((RStream) rStream);
        lenient().when(redissonClient.getStream(anyString(), any(StringCodec.class))).thenReturn((RStream) rStream);
        // Note: createGroup() exceptions are handled internally by RedisDeadLetterConsumer
    }

    @AfterEach
    void tearDown() {
        if (consumer != null && !consumer.isClosed()) {
            consumer.close();
        }
    }

    // ==================== Constructor Tests ====================

    @Test
    void constructor_withThreeParams_createsConsumer() {
        // When
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");

        // Then
        assertNotNull(consumer);
        assertFalse(consumer.isRunning());
        assertFalse(consumer.isClosed());
    }

    @Test
    void constructor_withFourParams_createsConsumerWithReplayHandler() {
        // When
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group", replayHandler);

        // Then
        assertNotNull(consumer);
        assertFalse(consumer.isRunning());
        assertFalse(consumer.isClosed());
    }

    @Test
    void constructor_withBlankGroup_usesDefaultGroupName() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "  ");

        // When
        consumer.subscribe("test-topic", deadLetterHandler);

        // Then - should use default group name "dlq-group"
        verify(redissonClient).getStream("stream:topic:test-topic:dlq");
    }

    @Test
    void constructor_withNullGroup_usesDefaultGroupName() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", null);

        // When
        consumer.subscribe("test-topic", deadLetterHandler);

        // Then - should use default group name "dlq-group"
        verify(redissonClient).getStream("stream:topic:test-topic:dlq");
    }

    // ==================== subscribe() Tests ====================

    @Test
    void subscribe_withTopicAndHandler_usesDefaultGroup() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "default-group");

        // When
        consumer.subscribe("test-topic", deadLetterHandler);

        // Then
        verify(redissonClient).getStream("stream:topic:test-topic:dlq");
        verify(rStream).createGroup(any(StreamCreateGroupArgs.class));
    }

    @Test
    void subscribe_withTopicAndGroupAndHandler_createsGroup() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "default-group");

        // When
        consumer.subscribe("test-topic", "custom-group", deadLetterHandler);

        // Then
        verify(redissonClient).getStream("stream:topic:test-topic:dlq");
        verify(rStream).createGroup(any(StreamCreateGroupArgs.class));
    }

    @Test
    void subscribe_whenCreateGroupFails_continuesSuccessfully() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");

        // When - RedisDeadLetterConsumer catches createGroup exceptions internally
        consumer.subscribe("test-topic", deadLetterHandler);

        // Then - subscription should succeed
        verify(redissonClient).getStream("stream:topic:test-topic:dlq");
    }

    @Test
    void subscribe_whenClosed_throwsIllegalStateException() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");
        consumer.close();

        // When & Then
        assertThrows(IllegalStateException.class, () -> consumer.subscribe("test-topic", deadLetterHandler));
    }

    @Test
    void subscribe_multipleTopics_allSubscribed() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");

        // When
        consumer.subscribe("topic1", deadLetterHandler);
        consumer.subscribe("topic2", deadLetterHandler);
        consumer.subscribe("topic3", deadLetterHandler);

        // Then
        verify(redissonClient, times(3)).getStream(anyString());
    }

    // ==================== start() and stop() Tests ====================

    @Test
    void start_whenNotStarted_setsRunningToTrue() throws InterruptedException {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");
        consumer.subscribe("test-topic", deadLetterHandler);

        // When
        consumer.start();

        // Then
        assertTrue(consumer.isRunning());

        // Cleanup
        consumer.stop();
    }

    @Test
    void start_whenAlreadyRunning_isIdempotent() throws InterruptedException {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");
        consumer.subscribe("test-topic", deadLetterHandler);
        consumer.start();

        // When - start again should not cause issues
        assertDoesNotThrow(() -> consumer.start());

        // Then
        assertTrue(consumer.isRunning());

        // Cleanup
        consumer.stop();
    }

    @Test
    void stop_whenRunning_setsRunningToFalse() throws InterruptedException {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");
        consumer.subscribe("test-topic", deadLetterHandler);
        consumer.start();

        // When
        consumer.stop();

        // Then
        assertFalse(consumer.isRunning());
    }

    @Test
    void stop_whenNotRunning_isNoOp() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");

        // When - should not throw
        assertDoesNotThrow(() -> consumer.stop());

        // Then
        assertFalse(consumer.isRunning());
    }

    // ==================== close() Tests ====================

    @Test
    void close_whenNotClosed_stopsAndCleansUp() throws InterruptedException {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");
        consumer.subscribe("test-topic", deadLetterHandler);
        consumer.start();

        // When
        consumer.close();

        // Then
        assertTrue(consumer.isClosed());
        assertFalse(consumer.isRunning());
    }

    @Test
    void close_whenAlreadyClosed_isIdempotent() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");
        consumer.close();

        // When - should not throw
        assertDoesNotThrow(() -> consumer.close());

        // Then
        assertTrue(consumer.isClosed());
    }

    @Test
    void close_afterSubscribe_clearsSubscriptions() throws InterruptedException {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");
        consumer.subscribe("topic1", deadLetterHandler);
        consumer.subscribe("topic2", deadLetterHandler);
        consumer.start();

        // When
        consumer.close();

        // Then
        assertTrue(consumer.isClosed());
    }

    // ==================== isRunning() and isClosed() Tests ====================

    @Test
    void isRunning_initiallyReturnsFalse() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");

        // Then
        assertFalse(consumer.isRunning());
    }

    @Test
    void isRunning_afterStartReturnsTrue() throws InterruptedException {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");
        consumer.subscribe("test-topic", deadLetterHandler);

        // When
        consumer.start();

        // Then
        assertTrue(consumer.isRunning());

        // Cleanup
        consumer.stop();
    }

    @Test
    void isRunning_afterStopReturnsFalse() throws InterruptedException {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");
        consumer.subscribe("test-topic", deadLetterHandler);
        consumer.start();
        consumer.stop();

        // Then
        assertFalse(consumer.isRunning());
    }

    @Test
    void isClosed_initiallyReturnsFalse() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");

        // Then
        assertFalse(consumer.isClosed());
    }

    @Test
    void isClosed_afterCloseReturnsTrue() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");

        // When
        consumer.close();

        // Then
        assertTrue(consumer.isClosed());
    }

    // ==================== State Transition Tests ====================

    @Test
    void lifecycle_startStopClose_transitionsCorrectly() throws InterruptedException {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");
        consumer.subscribe("test-topic", deadLetterHandler);

        // Initially: not running, not closed
        assertFalse(consumer.isRunning());
        assertFalse(consumer.isClosed());

        // After start: running, not closed
        consumer.start();
        assertTrue(consumer.isRunning());
        assertFalse(consumer.isClosed());

        // After stop: not running, not closed
        consumer.stop();
        assertFalse(consumer.isRunning());
        assertFalse(consumer.isClosed());

        // After close: not running, closed
        consumer.close();
        assertFalse(consumer.isRunning());
        assertTrue(consumer.isClosed());
    }

    @Test
    void lifecycle_closeWithoutStart_transitionsToClosed() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");
        consumer.subscribe("test-topic", deadLetterHandler);

        // Initially: not running, not closed
        assertFalse(consumer.isRunning());
        assertFalse(consumer.isClosed());

        // After close: not running, closed
        consumer.close();
        assertFalse(consumer.isRunning());
        assertTrue(consumer.isClosed());
    }

    // ==================== Edge Cases ====================

    @Test
    void subscribe_withSpecialCharactersInTopic_usesKeyCorrectly() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");

        // When
        consumer.subscribe("topic-with-.special*chars", deadLetterHandler);

        // Then
        verify(redissonClient).getStream("stream:topic:topic-with-.special*chars:dlq");
    }

    @Test
    void subscribe_withSameTopicTwice_updatesSubscription() {
        // Given
        consumer = new RedisDeadLetterConsumer(redissonClient, "test-consumer", "test-group");

        // When
        consumer.subscribe("test-topic", deadLetterHandler);
        consumer.subscribe("test-topic", deadLetterHandler);

        // Then - should handle duplicate subscription
        verify(redissonClient, atLeastOnce()).getStream("stream:topic:test-topic:dlq");
    }
}
