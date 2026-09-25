package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.Partitioner;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamMessageId;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link RedisMessageProducer}: forced-partition fallback when
 * the partition count is not usable.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisMessageProducerSprintCoverageTest {

    private RedissonClient client;
    private Partitioner partitioner;
    private TopicPartitionRegistry partitionRegistry;
    private RStream<String, Object> stream;

    @BeforeEach
    void setUp() throws Exception {
        io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.configure("streaming:mq", "stream:topic");
        client = mock(RedissonClient.class);
        partitioner = mock(Partitioner.class);
        partitionRegistry = mock(TopicPartitionRegistry.class);
        stream = mock(RStream.class);
        when(client.getStream(anyString(), any())).thenReturn((RStream) stream);
        when(stream.add(any())).thenReturn(new StreamMessageId(7, 0));
    }

    @Test
    void forcedPartitionFallsBackToZeroWhenPartitionCountUnusable() throws Exception {
        when(partitionRegistry.getPartitionCount("t")).thenReturn(0);
        Message m = new Message();
        m.setTopic("t");
        m.setPayload("p");
        m.setHeaders(new HashMap<>());
        m.getHeaders().put(MqHeaders.FORCE_PARTITION_ID, "5");

        RedisMessageProducer producer = new RedisMessageProducer(client, partitioner, partitionRegistry,
                MqOptions.builder().build());
        String id = producer.send(m).get();
        assertNotNull(id);

        var captor = org.mockito.ArgumentCaptor.forClass(StreamAddArgs.class);
        verify(stream).add(captor.capture());
        Map<String, Object> entries = extractEntries(captor.getValue());
        assertEquals(0, entries.get("partitionId"));
    }

    private static Map<String, Object> extractEntries(Object addArgs) throws Exception {
        for (Class<?> c = addArgs.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (Map<String, Object>) f.get(addArgs);
                }
            }
        }
        throw new IllegalStateException("no entries map found on " + addArgs.getClass());
    }
}
