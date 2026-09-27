package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MQ-12 regression: listTopics() walked the ENTIRE keyspace via the plain no-arg
 * getKeys() even though the DLQ pattern was already computed — on a large shared Redis
 * every admin call paid a blocking full-keyspace SCAN of unrelated keys. The scan must
 * be scoped to the DLQ pattern; the manual prefix/suffix filter stays on top of it.
 */
class RedisDeadLetterAdminScanPatternTest {

    @Test
    void listTopicsScansOnlyTheDlqPattern() {
        RedissonClient client = mock(RedissonClient.class);
        RKeys keys = mock(RKeys.class);
        when(client.getKeys()).thenReturn(keys);
        // decoys prove the manual filter stays on top of the patterned scan
        when(keys.getKeys(any(KeysScanOptions.class))).thenReturn(Arrays.asList(
                "stream:topic:orders:dlq",
                "stream:topic:billing:dlq",
                "stream:topic:orders:p:0",      // same stream prefix, not a DLQ key
                "streaming:mq:lease:t:dlq"));   // control-prefix decoy ending in :dlq

        List<String> topics = new RedisDeadLetterAdmin(client, null).listTopics();

        assertEquals(List.of("orders", "billing"), topics);
        verify(keys).getKeys(any(KeysScanOptions.class));
        verify(keys, never()).getKeys();
    }
}
