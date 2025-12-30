package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HashBrokerRouter
 */
class HashBrokerRouterTest {

    private final HashBrokerRouter router = new HashBrokerRouter();

    @Test
    void testRoutePartitionWithValidPartitionCount() {
        int partition = router.routePartition("test-topic", "test-key", null, 10);

        assertTrue(partition >= 0 && partition < 10);
    }

    @Test
    void testRoutePartitionWithZeroPartitionCount() {
        int partition = router.routePartition("test-topic", "test-key", null, 0);

        assertEquals(0, partition);
    }

    @Test
    void testRoutePartitionWithNegativePartitionCount() {
        int partition = router.routePartition("test-topic", "test-key", null, -5);

        assertEquals(0, partition);
    }

    @Test
    void testRoutePartitionWithSinglePartition() {
        int partition = router.routePartition("test-topic", "test-key", null, 1);

        assertEquals(0, partition);
    }

    @Test
    void testRoutePartitionWithNullKey() {
        int partition = router.routePartition("test-topic", null, null, 10);

        assertTrue(partition >= 0 && partition < 10);
    }

    @Test
    void testRoutePartitionWithEmptyKey() {
        int partition = router.routePartition("test-topic", "", null, 10);

        assertTrue(partition >= 0 && partition < 10);
    }

    @Test
    void testRoutePartitionWithForcedPartitionHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put(MqHeaders.FORCE_PARTITION_ID, "5");

        int partition = router.routePartition("test-topic", "any-key", headers, 10);

        assertEquals(5, partition);
    }

    @Test
    void testRoutePartitionWithForcedPartitionHeaderExceedsCount() {
        Map<String, String> headers = new HashMap<>();
        headers.put(MqHeaders.FORCE_PARTITION_ID, "15");

        int partition = router.routePartition("test-topic", "any-key", headers, 10);

        assertEquals(5, partition); // 15 % 10 = 5
    }

    @Test
    void testRoutePartitionWithNegativeForcedPartition() {
        Map<String, String> headers = new HashMap<>();
        headers.put(MqHeaders.FORCE_PARTITION_ID, "-3");

        int partition = router.routePartition("test-topic", "any-key", headers, 10);

        assertEquals(7, partition); // -3 % 10 = -3, -3 + 10 = 7
    }

    @Test
    void testRoutePartitionWithInvalidForcedPartition() {
        Map<String, String> headers = new HashMap<>();
        headers.put(MqHeaders.FORCE_PARTITION_ID, "invalid");

        int partition = router.routePartition("test-topic", "test-key", headers, 10);

        // Should fall back to hash partitioning
        assertTrue(partition >= 0 && partition < 10);
    }

    @Test
    void testRoutePartitionWithEmptyForcedPartition() {
        Map<String, String> headers = new HashMap<>();
        headers.put(MqHeaders.FORCE_PARTITION_ID, "");

        int partition = router.routePartition("test-topic", "test-key", headers, 10);

        // Should fall back to hash partitioning
        assertTrue(partition >= 0 && partition < 10);
    }

    @Test
    void testRoutePartitionWithNullForcedPartition() {
        Map<String, String> headers = new HashMap<>();
        headers.put(MqHeaders.FORCE_PARTITION_ID, null);

        int partition = router.routePartition("test-topic", "test-key", headers, 10);

        // Should fall back to hash partitioning
        assertTrue(partition >= 0 && partition < 10);
    }

    @Test
    void testRoutePartitionWithEmptyHeaders() {
        Map<String, String> headers = new HashMap<>();

        int partition = router.routePartition("test-topic", "test-key", headers, 10);

        assertTrue(partition >= 0 && partition < 10);
    }

    @Test
    void testRoutePartitionConsistentWithSameKey() {
        int partition1 = router.routePartition("test-topic", "same-key", null, 100);
        int partition2 = router.routePartition("test-topic", "same-key", null, 100);

        assertEquals(partition1, partition2);
    }

    @Test
    void testRoutePartitionDifferentWithDifferentKeys() {
        int partition1 = router.routePartition("test-topic", "key-one", null, 100);
        int partition2 = router.routePartition("test-topic", "key-two", null, 100);

        // Different keys may or may not map to different partitions,
        // but they should both be valid
        assertTrue(partition1 >= 0 && partition1 < 100);
        assertTrue(partition2 >= 0 && partition2 < 100);
    }

    @Test
    void testRoutePartitionWithForcedPartitionZero() {
        Map<String, String> headers = new HashMap<>();
        headers.put(MqHeaders.FORCE_PARTITION_ID, "0");

        int partition = router.routePartition("test-topic", "any-key", headers, 10);

        assertEquals(0, partition);
    }

    @Test
    void testRoutePartitionWithLargeForcedPartition() {
        Map<String, String> headers = new HashMap<>();
        headers.put(MqHeaders.FORCE_PARTITION_ID, "1000");

        int partition = router.routePartition("test-topic", "any-key", headers, 10);

        assertEquals(0, partition); // 1000 % 10 = 0
    }

    @Test
    void testRoutePartitionWithNegativeLargeForcedPartition() {
        Map<String, String> headers = new HashMap<>();
        headers.put(MqHeaders.FORCE_PARTITION_ID, "-1001");

        int partition = router.routePartition("test-topic", "any-key", headers, 10);

        assertEquals(9, partition); // -1001 % 10 = -1, -1 + 10 = 9
    }
}
