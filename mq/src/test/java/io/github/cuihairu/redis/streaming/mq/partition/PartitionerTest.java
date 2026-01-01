package io.github.cuihairu.redis.streaming.mq.partition;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Partitioner interface
 */
class PartitionerTest {

    @Test
    void testInterfaceHasPartitionMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(Partitioner.class.getMethod("partition", String.class, int.class));
    }

    @Test
    void testSimpleImplementation() {
        // Given
        Partitioner partitioner = new Partitioner() {
            @Override
            public int partition(String key, int partitionCount) {
                if (key == null) {
                    return 0;
                }
                return Math.abs(key.hashCode()) % partitionCount;
            }
        };

        // When & Then
        assertEquals(0, partitioner.partition("test", 1));
        assertTrue(partitioner.partition("test", 10) >= 0);
        assertTrue(partitioner.partition("test", 10) < 10);
    }

    @Test
    void testPartitionWithNullKey() {
        // Given
        Partitioner partitioner = new Partitioner() {
            @Override
            public int partition(String key, int partitionCount) {
                return key == null ? 0 : Math.abs(key.hashCode()) % partitionCount;
            }
        };

        // When & Then
        assertEquals(0, partitioner.partition(null, 10));
    }

    @Test
    void testPartitionRange() {
        // Given
        Partitioner partitioner = new Partitioner() {
            @Override
            public int partition(String key, int partitionCount) {
                return Math.abs(key.hashCode()) % partitionCount;
            }
        };

        // When & Then - partition should always be in range [0, partitionCount)
        for (int i = 1; i <= 100; i++) {
            int partition = partitioner.partition("key-" + i, 10);
            assertTrue(partition >= 0 && partition < 10,
                    "Partition " + partition + " should be in range [0, 10)");
        }
    }

    @Test
    void testPartitionWithSinglePartition() {
        // Given
        Partitioner partitioner = new Partitioner() {
            @Override
            public int partition(String key, int partitionCount) {
                return 0; // Always return 0 for single partition
            }
        };

        // When & Then
        assertEquals(0, partitioner.partition("any-key", 1));
        assertEquals(0, partitioner.partition(null, 1));
    }

    @Test
    void testPartitionDeterministic() {
        // Given
        Partitioner partitioner = new Partitioner() {
            @Override
            public int partition(String key, int partitionCount) {
                return key == null ? 0 : Math.abs(key.hashCode()) % partitionCount;
            }
        };

        // When & Then - same key should always map to same partition
        String key = "test-key";
        int partitionCount = 50;

        int partition1 = partitioner.partition(key, partitionCount);
        int partition2 = partitioner.partition(key, partitionCount);
        int partition3 = partitioner.partition(key, partitionCount);

        assertEquals(partition1, partition2);
        assertEquals(partition2, partition3);
    }

    @Test
    void testPartitionDifferentKeysDifferentPartitions() {
        // Given
        Partitioner partitioner = new Partitioner() {
            @Override
            public int partition(String key, int partitionCount) {
                return key == null ? 0 : Math.abs(key.hashCode()) % partitionCount;
            }
        };

        // When
        int partition1 = partitioner.partition("key1", 100);
        int partition2 = partitioner.partition("key2", 100);
        int partition3 = partitioner.partition("key3", 100);

        // Then - different keys might map to different partitions
        // (not guaranteed due to hash collisions, but likely)
        int uniquePartitions = (partition1 == partition2 ? 1 : 0) +
                               (partition1 == partition3 ? 1 : 0) +
                               (partition2 == partition3 ? 1 : 0);
        assertTrue(uniquePartitions < 3, "Keys should distribute across partitions");
    }

    @Test
    void testPartitionWithEmptyString() {
        // Given
        Partitioner partitioner = new Partitioner() {
            @Override
            public int partition(String key, int partitionCount) {
                return Math.abs(key.hashCode()) % partitionCount;
            }
        };

        // When & Then - empty string is still a valid key
        int partition = partitioner.partition("", 10);
        assertTrue(partition >= 0 && partition < 10);
    }

    @Test
    void testPartitionWithSpecialCharacters() {
        // Given
        Partitioner partitioner = new Partitioner() {
            @Override
            public int partition(String key, int partitionCount) {
                return Math.abs(key.hashCode()) % partitionCount;
            }
        };

        // When & Then - special characters should work
        String[] specialKeys = {"!@#$%", "中文", "😀🎉", "null", "", " "};
        int partitionCount = 20;

        for (String key : specialKeys) {
            int partition = partitioner.partition(key, partitionCount);
            assertTrue(partition >= 0 && partition < partitionCount,
                    "Partition for '" + key + "' should be in range [0, " + partitionCount + ")");
        }
    }

    @Test
    void testMultiplePartitionCounts() {
        // Given
        Partitioner partitioner = new Partitioner() {
            @Override
            public int partition(String key, int partitionCount) {
                return key == null ? 0 : Math.abs(key.hashCode()) % partitionCount;
            }
        };

        String key = "test-key";

        // When & Then - should work with different partition counts
        assertTrue(partitioner.partition(key, 1) >= 0 && partitioner.partition(key, 1) < 1);
        assertTrue(partitioner.partition(key, 2) >= 0 && partitioner.partition(key, 2) < 2);
        assertTrue(partitioner.partition(key, 10) >= 0 && partitioner.partition(key, 10) < 10);
        assertTrue(partitioner.partition(key, 100) >= 0 && partitioner.partition(key, 100) < 100);
        assertTrue(partitioner.partition(key, 1000) >= 0 && partitioner.partition(key, 1000) < 1000);
    }

    @Test
    void testPartitionUniformDistribution() {
        // Given
        Partitioner partitioner = new Partitioner() {
            @Override
            public int partition(String key, int partitionCount) {
                return key == null ? 0 : Math.abs(key.hashCode()) % partitionCount;
            }
        };

        int partitionCount = 10;
        int samples = 1000;
        int[] distribution = new int[partitionCount];

        // When - distribute many keys
        for (int i = 0; i < samples; i++) {
            int partition = partitioner.partition("key-" + i, partitionCount);
            distribution[partition]++;
        }

        // Then - should have relatively uniform distribution
        // Each partition should have roughly samples/partitionCount entries
        int expectedPerPartition = samples / partitionCount;
        for (int i = 0; i < partitionCount; i++) {
            // Allow 50% deviation from expected
            assertTrue(distribution[i] >= expectedPerPartition / 2,
                    "Partition " + i + " has too few samples: " + distribution[i]);
            assertTrue(distribution[i] <= expectedPerPartition * 3 / 2,
                    "Partition " + i + " has too many samples: " + distribution[i]);
        }
    }

    @Test
    void testRoundRobinPartitioner() {
        // Given - alternative implementation: round-robin
        class RoundRobinPartitioner implements Partitioner {
            private int current = 0;

            @Override
            public int partition(String key, int partitionCount) {
                int result = current % partitionCount;
                current++;
                return result;
            }
        }

        RoundRobinPartitioner partitioner = new RoundRobinPartitioner();

        // When & Then - should cycle through partitions
        assertEquals(0, partitioner.partition("any", 3));
        assertEquals(1, partitioner.partition("any", 3));
        assertEquals(2, partitioner.partition("any", 3));
        assertEquals(0, partitioner.partition("any", 3));
        assertEquals(1, partitioner.partition("any", 3));
    }

    @Test
    void testPartitionStateless() {
        // Given - stateless partitioner
        Partitioner partitioner = new Partitioner() {
            @Override
            public int partition(String key, int partitionCount) {
                return key == null ? 0 : Math.abs(key.hashCode()) % partitionCount;
            }
        };

        // When & Then - should be stateless (same key always same partition)
        for (int i = 0; i < 100; i++) {
            assertEquals(partitioner.partition("test", 10), partitioner.partition("test", 10));
        }
    }
}
