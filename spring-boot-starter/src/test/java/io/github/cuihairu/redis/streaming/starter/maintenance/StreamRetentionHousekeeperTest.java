package io.github.cuihairu.redis.streaming.starter.maintenance;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StreamRetentionHousekeeper
 */
class StreamRetentionHousekeeperTest {

    @Mock
    private RedissonClient mockRedissonClient;

    @Mock
    private MessageQueueAdmin mockAdmin;

    private MqOptions options;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        options = MqOptions.builder()
                .trimIntervalSec(10)
                .retentionMaxLenPerPartition(1000)
                .retentionMs(3600000)
                .dlqRetentionMaxLen(500)
                .dlqRetentionMs(7200000)
                .build();
    }

    // ===== Constructor Tests =====

    @Test
    void testConstructorWithValidParameters() {
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, options));
    }

    @Test
    void testConstructorWithNullRedissonClient() {
        assertThrows(NullPointerException.class, () ->
            new StreamRetentionHousekeeper(null, mockAdmin, options));
    }

    @Test
    void testConstructorWithNullAdmin() {
        assertThrows(NullPointerException.class, () ->
            new StreamRetentionHousekeeper(mockRedissonClient, null, options));
    }

    @Test
    void testConstructorWithNullOptions() {
        assertThrows(NullPointerException.class, () ->
            new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, null));
    }

    // ===== MqOptions Tests =====

    @Test
    void testConstructorWithDefaultOptions() {
        MqOptions defaultOptions = MqOptions.builder().build();
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, defaultOptions));
    }

    @Test
    void testConstructorWithZeroTrimInterval() {
        MqOptions opts = MqOptions.builder()
                .trimIntervalSec(0)
                .build();
        // Should still work (0 means no scheduled trimming)
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    @Test
    void testConstructorWithNegativeTrimInterval() {
        MqOptions opts = MqOptions.builder()
                .trimIntervalSec(-1)
                .build();
        // Should still work (negative value treated as no trimming)
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    @Test
    void testConstructorWithZeroRetention() {
        MqOptions opts = MqOptions.builder()
                .retentionMaxLenPerPartition(0)
                .retentionMs(0)
                .build();
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    @Test
    void testConstructorWithNegativeRetention() {
        MqOptions opts = MqOptions.builder()
                .retentionMaxLenPerPartition(-100)
                .retentionMs(-1000)
                .build();
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    @Test
    void testConstructorWithLargeRetentionValues() {
        MqOptions opts = MqOptions.builder()
                .retentionMaxLenPerPartition(10000000)
                .retentionMs(Long.MAX_VALUE)
                .build();
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    // ===== Close Tests =====

    @Test
    void testClose() throws Exception {
        StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, options);
        assertDoesNotThrow(() -> keeper.close());
    }

    @Test
    void testCloseMultipleTimes() throws Exception {
        StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, options);
        keeper.close();
        assertDoesNotThrow(() -> keeper.close());
    }

    // ===== runOnce Tests =====

    @Test
    void testRunOnce() throws Exception {
        StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, options);
        // runOnce will try to trim topics, should not throw even with mocks
        assertDoesNotThrow(() -> keeper.runOnce());
        keeper.close();
    }

    @Test
    void testRunOnceWithEmptyTopics() throws Exception {
        StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, options);
        assertDoesNotThrow(() -> keeper.runOnce());
        keeper.close();
    }

    // ===== DLQ Retention Tests =====

    @Test
    void testConstructorWithZeroDlqRetention() {
        MqOptions opts = MqOptions.builder()
                .dlqRetentionMaxLen(0)
                .dlqRetentionMs(0)
                .build();
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    @Test
    void testConstructorWithOnlyDlqMaxLength() {
        MqOptions opts = MqOptions.builder()
                .dlqRetentionMaxLen(1000)
                .dlqRetentionMs(0)
                .build();
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    @Test
    void testConstructorWithOnlyDlqRetentionMs() {
        MqOptions opts = MqOptions.builder()
                .dlqRetentionMaxLen(0)
                .dlqRetentionMs(3600000)
                .build();
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    // ===== Default Partition Count Tests =====

    @Test
    void testConstructorWithZeroDefaultPartitionCount() {
        MqOptions opts = MqOptions.builder()
                .defaultPartitionCount(0)
                .build();
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    @Test
    void testConstructorWithNegativeDefaultPartitionCount() {
        MqOptions opts = MqOptions.builder()
                .defaultPartitionCount(-1)
                .build();
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    @Test
    void testConstructorWithLargeDefaultPartitionCount() {
        MqOptions opts = MqOptions.builder()
                .defaultPartitionCount(1000)
                .build();
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    // ===== Edge Case Tests =====

    @Test
    void testConstructorWithAllZeroRetentions() {
        MqOptions opts = MqOptions.builder()
                .retentionMaxLenPerPartition(0)
                .retentionMs(0)
                .dlqRetentionMaxLen(0)
                .dlqRetentionMs(0)
                .build();
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    @Test
    void testConstructorWithVerySmallTrimInterval() {
        MqOptions opts = MqOptions.builder()
                .trimIntervalSec(1)
                .build();
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    @Test
    void testConstructorWithVeryLargeTrimInterval() {
        MqOptions opts = MqOptions.builder()
                .trimIntervalSec(86400) // 1 day
                .build();
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    @Test
    void testConstructorWithMinimalOptions() {
        MqOptions opts = new MqOptions();
        assertDoesNotThrow(() -> new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, opts));
    }

    // ===== Multiple Instances Tests =====

    @Test
    void testMultipleInstances() throws Exception {
        StreamRetentionHousekeeper keeper1 = new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, options);
        StreamRetentionHousekeeper keeper2 = new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, options);

        assertNotNull(keeper1);
        assertNotNull(keeper2);

        keeper1.close();
        keeper2.close();
    }

    // ===== AutoCloseable Tests =====

    @Test
    void testImplementsAutoCloseable() {
        StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, options);
        assertTrue(keeper instanceof AutoCloseable);
    }

    @Test
    void testTryWithResources() throws Exception {
        try (StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(mockRedissonClient, mockAdmin, options)) {
            assertNotNull(keeper);
        }
    }
}
