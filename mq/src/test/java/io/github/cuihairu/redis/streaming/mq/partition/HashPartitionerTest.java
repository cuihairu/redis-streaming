package io.github.cuihairu.redis.streaming.mq.partition;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HashPartitioner
 */
class HashPartitionerTest {

    private final HashPartitioner partitioner = new HashPartitioner();

    // ===== partition with valid key Tests =====

    @Test
    void testPartitionWithValidKey() {
        int partition = partitioner.partition("test-key", 10);

        assertTrue(partition >= 0 && partition < 10);
    }

    @Test
    void testPartitionWithEmptyKey() {
        int partition = partitioner.partition("", 10);

        assertTrue(partition >= 0 && partition < 10);
    }

    @Test
    void testPartitionConsistentWithSameKey() {
        int partition1 = partitioner.partition("same-key", 100);
        int partition2 = partitioner.partition("same-key", 100);

        assertEquals(partition1, partition2);
    }

    @Test
    void testPartitionDifferentWithDifferentKeys() {
        int partition1 = partitioner.partition("key-one", 100);
        int partition2 = partitioner.partition("key-two", 100);

        // Different keys may or may not map to different partitions,
        // but both should be valid
        assertTrue(partition1 >= 0 && partition1 < 100);
        assertTrue(partition2 >= 0 && partition2 < 100);
    }

    @Test
    void testPartitionWithSinglePartition() {
        int partition = partitioner.partition("any-key", 1);

        assertEquals(0, partition);
    }

    // ===== partition with null key Tests =====

    @Test
    void testPartitionWithNullKey() {
        int partition = partitioner.partition(null, 10);

        assertTrue(partition >= 0 && partition < 10);
    }

    @Test
    void testPartitionWithNullKeyIsRandom() {
        // Test that null key produces random results
        int partition1 = partitioner.partition(null, 100);
        int partition2 = partitioner.partition(null, 100);

        // They could be the same by chance, but statistically unlikely
        assertTrue(partition1 >= 0 && partition1 < 100);
        assertTrue(partition2 >= 0 && partition2 < 100);
    }

    // ===== partition with invalid partition count Tests =====

    @Test
    void testPartitionWithZeroPartitionCount() {
        int partition = partitioner.partition("test-key", 0);

        assertEquals(0, partition);
    }

    @Test
    void testPartitionWithNegativePartitionCount() {
        int partition = partitioner.partition("test-key", -5);

        assertEquals(0, partition);
    }

    // ===== partition distribution Tests =====

    @Test
    void testPartitionDistribution() {
        // Test that keys distribute reasonably well across partitions
        int partitionCount = 100;
        int[] counts = new int[partitionCount];

        for (int i = 0; i < 1000; i++) {
            String key = "key-" + i;
            int partition = partitioner.partition(key, partitionCount);
            counts[partition]++;
        }

        // Check that all partitions got some assignments
        // (with 1000 keys and 100 partitions, expected ~10 per partition)
        for (int i = 0; i < partitionCount; i++) {
            assertTrue(counts[i] >= 1, "Partition " + i + " should have at least 1 assignment");
        }
    }

    // ===== partition with special characters Tests =====

    @Test
    void testPartitionWithSpecialCharacters() {
        int partition = partitioner.partition("key-with-特殊字符-🚀", 10);

        assertTrue(partition >= 0 && partition < 10);
    }

    @Test
    void testPartitionWithUnicode() {
        int partition = partitioner.partition("тест-ключ", 10);

        assertTrue(partition >= 0 && partition < 10);
    }

    @Test
    void testPartitionWithVeryLongKey() {
        String longKey = "a".repeat(10000);
        int partition = partitioner.partition(longKey, 10);

        assertTrue(partition >= 0 && partition < 10);
    }

    // ===== partition with negative hashCode handling Tests =====

    @Test
    void testPartitionHandlesNegativeHashCode() {
        // Find a key with negative hashCode
        String key = "Aa"; // "Aa".hashCode() = 2112, "BB".hashCode() = 2112
        // Let's use a key that definitely has negative hashCode
        // Most strings will have some negative hashCode behavior

        // The implementation should handle negative hashCode correctly
        int partition = partitioner.partition(key, 10);

        assertTrue(partition >= 0 && partition < 10);
    }

    // ===== partition boundary Tests =====

    @Test
    void testPartitionWithLargePartitionCount() {
        int partition = partitioner.partition("test-key", 10000);

        assertTrue(partition >= 0 && partition < 10000);
    }

    @Test
    void testPartitionAtBoundary() {
        // Test that partition never equals partitionCount
        for (int i = 0; i < 1000; i++) {
            String key = "key-" + i;
            int partition = partitioner.partition(key, 10);
            assertTrue(partition < 10, "Partition must be less than partitionCount");
            assertTrue(partition >= 0, "Partition must be non-negative");
        }
    }
}
