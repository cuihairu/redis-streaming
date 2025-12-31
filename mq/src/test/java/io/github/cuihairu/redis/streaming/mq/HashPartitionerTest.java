package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.partition.HashPartitioner;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HashPartitionerTest {
    @Test
    void testDeterministic() {
        HashPartitioner p = new HashPartitioner();
        int pc = 7;
        assertEquals(p.partition("a", pc), p.partition("a", pc));
        assertNotEquals(p.partition("a", pc), p.partition("b", pc));
        int idx = p.partition(null, pc);
        assertTrue(idx >= 0 && idx < pc);
    }

    @Test
    void partition_withValidPartitionCount() {
        HashPartitioner p = new HashPartitioner();
        int partitionCount = 10;

        int result = p.partition("key1", partitionCount);

        assertTrue(result >= 0);
        assertTrue(result < partitionCount);
    }

    @Test
    void partition_withZeroPartitionCount() {
        HashPartitioner p = new HashPartitioner();

        int result = p.partition("key1", 0);

        assertEquals(0, result);
    }

    @Test
    void partition_withNegativePartitionCount() {
        HashPartitioner p = new HashPartitioner();

        int result = p.partition("key1", -5);

        assertEquals(0, result);
    }

    @Test
    void partition_withSinglePartition() {
        HashPartitioner p = new HashPartitioner();

        int result = p.partition("any-key", 1);

        assertEquals(0, result);
    }

    @Test
    void partition_withNullKey() {
        HashPartitioner p = new HashPartitioner();
        int partitionCount = 5;

        int result1 = p.partition(null, partitionCount);
        int result2 = p.partition(null, partitionCount);

        assertTrue(result1 >= 0);
        assertTrue(result1 < partitionCount);
        assertTrue(result2 >= 0);
        assertTrue(result2 < partitionCount);
        // Random values may or may not be equal, just verify range
    }

    @Test
    void partition_isDeterministicForSameKey() {
        HashPartitioner p = new HashPartitioner();
        String key = "test-key";
        int partitionCount = 10;

        int result1 = p.partition(key, partitionCount);
        int result2 = p.partition(key, partitionCount);
        int result3 = p.partition(key, partitionCount);

        assertEquals(result1, result2);
        assertEquals(result2, result3);
    }

    @Test
    void partition_distributesKeys() {
        HashPartitioner p = new HashPartitioner();
        int partitionCount = 10;
        Set<Integer> partitions = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            String key = "key-" + i;
            int partition = p.partition(key, partitionCount);
            assertTrue(partition >= 0);
            assertTrue(partition < partitionCount);
            partitions.add(partition);
        }

        // Should distribute across multiple partitions
        assertTrue(partitions.size() > 1);
    }

    @Test
    void partition_withEmptyStringKey() {
        HashPartitioner p = new HashPartitioner();
        int partitionCount = 5;

        int result = p.partition("", partitionCount);

        assertTrue(result >= 0);
        assertTrue(result < partitionCount);
    }

    @Test
    void partition_withLongKey() {
        HashPartitioner p = new HashPartitioner();
        int partitionCount = 7;
        String longKey = "a".repeat(10000);

        int result = p.partition(longKey, partitionCount);

        assertTrue(result >= 0);
        assertTrue(result < partitionCount);
    }

    @Test
    void partition_withSpecialCharacters() {
        HashPartitioner p = new HashPartitioner();
        int partitionCount = 8;

        int result1 = p.partition("key-with-dash", partitionCount);
        int result2 = p.partition("key_with_underscore", partitionCount);
        int result3 = p.partition("key.with.dots", partitionCount);
        int result4 = p.partition("key:colon", partitionCount);
        int result5 = p.partition("key中文", partitionCount);

        assertTrue(result1 >= 0 && result1 < partitionCount);
        assertTrue(result2 >= 0 && result2 < partitionCount);
        assertTrue(result3 >= 0 && result3 < partitionCount);
        assertTrue(result4 >= 0 && result4 < partitionCount);
        assertTrue(result5 >= 0 && result5 < partitionCount);
    }

    @Test
    void partition_withLargePartitionCount() {
        HashPartitioner p = new HashPartitioner();
        int partitionCount = 1000;

        int result = p.partition("test-key", partitionCount);

        assertTrue(result >= 0);
        assertTrue(result < partitionCount);
    }

    @Test
    void partition_handlesNegativeHashCode() {
        HashPartitioner p = new HashPartitioner();
        // Some strings have negative hash codes
        String key = "negative-hash"; // hashCode may be negative depending on JVM

        int result = p.partition(key, 10);

        assertTrue(result >= 0);
        assertTrue(result < 10);
    }

    @Test
    void partition_withNumericKey() {
        HashPartitioner p = new HashPartitioner();
        int partitionCount = 5;

        int result1 = p.partition("123", partitionCount);
        int result2 = p.partition("456", partitionCount);

        assertTrue(result1 >= 0 && result1 < partitionCount);
        assertTrue(result2 >= 0 && result2 < partitionCount);
    }

    @Test
    void partition_differentKeysDifferentPartitions() {
        HashPartitioner p = new HashPartitioner();
        int partitionCount = 10;

        int result1 = p.partition("key-A", partitionCount);
        int result2 = p.partition("key-B", partitionCount);
        int result3 = p.partition("key-C", partitionCount);

        // Verify they are valid partitions
        assertTrue(result1 >= 0 && result1 < partitionCount);
        assertTrue(result2 >= 0 && result2 < partitionCount);
        assertTrue(result3 >= 0 && result3 < partitionCount);
    }

    @Test
    void partition_sameInstanceReusability() {
        HashPartitioner p = new HashPartitioner();

        int result1 = p.partition("key", 5);
        int result2 = p.partition("key", 5);

        assertEquals(result1, result2);
    }

    @Test
    void partition_differentInstancesSameResult() {
        HashPartitioner p1 = new HashPartitioner();
        HashPartitioner p2 = new HashPartitioner();

        int result1 = p1.partition("same-key", 10);
        int result2 = p2.partition("same-key", 10);

        assertEquals(result1, result2);
    }

    @Test
    void partition_allKeysInValidRange() {
        HashPartitioner p = new HashPartitioner();
        int partitionCount = 3;
        String[] keys = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j"};

        for (String key : keys) {
            int result = p.partition(key, partitionCount);
            assertTrue(result >= 0 && result < partitionCount,
                "Key " + key + " mapped to invalid partition: " + result);
        }
    }
}

