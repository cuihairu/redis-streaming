package io.github.cuihairu.redis.streaming.mq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SubscriptionOptions
 */
class SubscriptionOptionsTest {

    @Test
    void testBuilderCreatesInstance() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder().build();

        // Then
        assertNotNull(options);
        assertNull(options.getBatchCount());
        assertNull(options.getPollTimeoutMs());
        assertNull(options.getPartitionModulo());
        assertNull(options.getPartitionRemainder());
    }

    @Test
    void testBuilderWithBatchCount() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .batchCount(10)
                .build();

        // Then
        assertEquals(10, options.getBatchCount());
    }

    @Test
    void testBuilderWithPollTimeoutMs() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .pollTimeoutMs(5000L)
                .build();

        // Then
        assertEquals(5000L, options.getPollTimeoutMs());
    }

    @Test
    void testBuilderWithPartitionModulo() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .partitionModulo(3)
                .build();

        // Then
        assertEquals(3, options.getPartitionModulo());
    }

    @Test
    void testBuilderWithPartitionRemainder() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .partitionRemainder(1)
                .build();

        // Then
        assertEquals(1, options.getPartitionRemainder());
    }

    @Test
    void testBuilderWithAllOptions() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .batchCount(100)
                .pollTimeoutMs(10000L)
                .partitionModulo(5)
                .partitionRemainder(2)
                .build();

        // Then
        assertEquals(100, options.getBatchCount());
        assertEquals(10000L, options.getPollTimeoutMs());
        assertEquals(5, options.getPartitionModulo());
        assertEquals(2, options.getPartitionRemainder());
    }

    @Test
    void testBuilderWithChainedCalls() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .batchCount(50)
                .pollTimeoutMs(2000L)
                .partitionModulo(4)
                .partitionRemainder(3)
                .batchCount(75)  // Override previous value
                .build();

        // Then
        assertEquals(75, options.getBatchCount());
        assertEquals(2000L, options.getPollTimeoutMs());
        assertEquals(4, options.getPartitionModulo());
        assertEquals(3, options.getPartitionRemainder());
    }

    @Test
    void testBatchCountValidationWithZero() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .batchCount(0)
                .build();

        // Then - should be normalized to 1 (minimum)
        assertEquals(1, options.getBatchCount());
    }

    @Test
    void testBatchCountValidationWithNegative() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .batchCount(-10)
                .build();

        // Then - should be normalized to 1 (minimum)
        assertEquals(1, options.getBatchCount());
    }

    @Test
    void testPollTimeoutMsValidationWithNegative() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .pollTimeoutMs(-100L)
                .build();

        // Then - should be normalized to 0 (minimum)
        assertEquals(0L, options.getPollTimeoutMs());
    }

    @Test
    void testPartitionModuloValidationWithZero() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .partitionModulo(0)
                .build();

        // Then - should be normalized to 1 (minimum)
        assertEquals(1, options.getPartitionModulo());
    }

    @Test
    void testPartitionModuloValidationWithNegative() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .partitionModulo(-5)
                .build();

        // Then - should be normalized to 1 (minimum)
        assertEquals(1, options.getPartitionModulo());
    }

    @Test
    void testPartitionRemainderValidationWithNegative() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .partitionRemainder(-1)
                .build();

        // Then - should be normalized to 0 (minimum)
        assertEquals(0, options.getPartitionRemainder());
    }

    @Test
    void testMultipleBuildersCreateIndependentInstances() {
        // Given
        SubscriptionOptions options1 = SubscriptionOptions.builder()
                .batchCount(10)
                .build();

        SubscriptionOptions options2 = SubscriptionOptions.builder()
                .batchCount(20)
                .build();

        // When
        options1 = SubscriptionOptions.builder()
                .batchCount(30)
                .build();

        // Then - options2 should not be affected
        assertEquals(30, options1.getBatchCount());
        assertEquals(20, options2.getBatchCount());
    }

    @Test
    void testDefaultValuesAreNull() {
        // Given & When
        SubscriptionOptions options = new SubscriptionOptions();

        // Then
        assertNull(options.getBatchCount());
        assertNull(options.getPollTimeoutMs());
        assertNull(options.getPartitionModulo());
        assertNull(options.getPartitionRemainder());
    }

    @Test
    void testBuilderReturnsNewInstanceEachTime() {
        // Given & When
        SubscriptionOptions options1 = SubscriptionOptions.builder().build();
        SubscriptionOptions options2 = SubscriptionOptions.builder().build();

        // Then - should be different instances
        assertNotSame(options1, options2);
    }

    @Test
    void testPartitionModuloAndRemainderCombination() {
        // Given & When - valid combination
        SubscriptionOptions options = SubscriptionOptions.builder()
                .partitionModulo(5)
                .partitionRemainder(2)
                .build();

        // Then
        assertEquals(5, options.getPartitionModulo());
        assertEquals(2, options.getPartitionRemainder());
    }

    @Test
    void testLargeValues() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .batchCount(1000000)
                .pollTimeoutMs(Long.MAX_VALUE)
                .partitionModulo(10000)
                .partitionRemainder(9999)
                .build();

        // Then - should accept large values
        assertEquals(1000000, options.getBatchCount());
        assertEquals(Long.MAX_VALUE, options.getPollTimeoutMs());
        assertEquals(10000, options.getPartitionModulo());
        assertEquals(9999, options.getPartitionRemainder());
    }

    @Test
    void testOnlyBatchCountSet() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .batchCount(25)
                .build();

        // Then
        assertEquals(25, options.getBatchCount());
        assertNull(options.getPollTimeoutMs());
        assertNull(options.getPartitionModulo());
        assertNull(options.getPartitionRemainder());
    }

    @Test
    void testOnlyPollTimeoutMsSet() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .pollTimeoutMs(3000L)
                .build();

        // Then
        assertNull(options.getBatchCount());
        assertEquals(3000L, options.getPollTimeoutMs());
        assertNull(options.getPartitionModulo());
        assertNull(options.getPartitionRemainder());
    }

    @Test
    void testOnlyPartitionSettingsSet() {
        // Given & When
        SubscriptionOptions options = SubscriptionOptions.builder()
                .partitionModulo(3)
                .partitionRemainder(1)
                .build();

        // Then
        assertNull(options.getBatchCount());
        assertNull(options.getPollTimeoutMs());
        assertEquals(3, options.getPartitionModulo());
        assertEquals(1, options.getPartitionRemainder());
    }

    @Test
    void testBuilderFluentApi() {
        // Given & When - test fluent API returns same builder instance
        SubscriptionOptions.Builder builder = SubscriptionOptions.builder();
        SubscriptionOptions.Builder result1 = builder.batchCount(10);
        SubscriptionOptions.Builder result2 = result1.pollTimeoutMs(1000L);
        SubscriptionOptions.Builder result3 = result2.partitionModulo(2);
        SubscriptionOptions.Builder result4 = result3.partitionRemainder(1);

        // Then - all should return the same builder instance
        assertSame(builder, result1);
        assertSame(builder, result2);
        assertSame(builder, result3);
        assertSame(builder, result4);
    }
}
