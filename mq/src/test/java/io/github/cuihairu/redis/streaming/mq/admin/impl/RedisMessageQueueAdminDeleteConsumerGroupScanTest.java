package io.github.cuihairu.redis.streaming.mq.admin.impl;

import io.github.cuihairu.redis.streaming.mq.admin.TopicRegistry;
import io.github.cuihairu.redis.streaming.mq.impl.PayloadLifecycleManager;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MQ-12 regression: deleteConsumerGroup()'s pc&lt;=1 fallback computed the partition
 * pattern but then scanned the ENTIRE keyspace via the plain no-arg getKeys() with a
 * manual prefix filter. The scan must be scoped to the pattern; on a large shared Redis
 * the full SCAN spiked admin latency and Redis load.
 */
class RedisMessageQueueAdminDeleteConsumerGroupScanTest {

    private RedissonClient client;
    private RKeys keys;
    private RStream<String, Object> partition0;
    private RStream<String, Object> partition1;
    private RedisMessageQueueAdmin admin;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(RedissonClient.class);
        keys = mock(RKeys.class);
        when(client.getKeys()).thenReturn(keys);

        TopicPartitionRegistry registry = mock(TopicPartitionRegistry.class);
        when(registry.getPartitionCount("t1")).thenReturn(1); // meta absent -> fallback scan branch

        partition0 = mock(RStream.class);
        partition1 = mock(RStream.class);
        when(client.getStream(eq("stream:topic:t1:p:0"), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RStream) partition0);
        when(client.getStream(eq("stream:topic:t1:p:1"), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RStream) partition1);
        when(partition0.isExists()).thenReturn(false); // p0 absent
        when(partition1.isExists()).thenReturn(true);  // p1 is discoverable ONLY via the scan

        admin = new RedisMessageQueueAdmin(client,
                mock(TopicRegistry.class), registry, mock(PayloadLifecycleManager.class));
    }

    @Test
    void deleteConsumerGroupScansPartitionPatternNotTheWholeKeyspace() {
        when(keys.getKeys(any(KeysScanOptions.class))).thenReturn(List.of("stream:topic:t1:p:1"));

        boolean attempted = admin.deleteConsumerGroup("t1", "g");

        assertTrue(attempted, "the scan-only partition p:1 must be found and its group removed");
        verify(partition1).removeGroup("g");
        verify(keys).getKeys(any(KeysScanOptions.class));
        verify(keys, never()).getKeys();
    }
}
