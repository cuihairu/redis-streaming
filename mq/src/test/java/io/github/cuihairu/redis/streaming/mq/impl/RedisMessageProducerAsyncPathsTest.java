package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.Partitioner;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;

import java.util.HashMap;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Covers remaining RedisMessageProducer.send() lambda branches: headers-less messages,
 * unparseable forced-partition headers and the failure path.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisMessageProducerAsyncPathsTest {

    @Mock
    private RedissonClient client;
    @Mock
    private Partitioner partitioner;
    @Mock
    private TopicPartitionRegistry partitionRegistry;
    @Mock
    private RStream<Object, Object> stream;

    private final StreamMessageId id = new StreamMessageId(11, 0);

    private RedisMessageProducer producer(MqOptions options) {
        MockitoAnnotations.openMocks(this);
        when(partitioner.partition(any(), anyInt())).thenReturn(0);
        when(partitionRegistry.getPartitionCount(anyString())).thenReturn(1);
        when(client.getStream(anyString(), any(org.redisson.client.codec.StringCodec.class)))
                .thenReturn((RStream) stream);
        when(stream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(id);
        return new RedisMessageProducer(client, partitioner, partitionRegistry,
                options != null ? options : MqOptions.builder().build());
    }

    @Test
    void sendWithoutHeadersUsesPartitioner() throws Exception {
        RedisMessageProducer p = producer(null);
        Message m = new Message();
        m.setTopic("t");
        m.setPayload("pay");
        String out = p.send(m).get();
        assertEquals(id.toString(), out);
        verify(partitioner).partition(isNull(), anyInt());
    }

    @Test
    void sendWithUnparseableForcedPartitionFallsBackToPartitioner() throws Exception {
        RedisMessageProducer p = producer(null);
        Message m = new Message();
        m.setTopic("t");
        m.setPayload("pay");
        m.setHeaders(new HashMap<>());
        m.getHeaders().put(MqHeaders.FORCE_PARTITION_ID, "not-a-number");
        String out = p.send(m).get();
        assertEquals(id.toString(), out);
        verify(partitioner).partition(any(), anyInt());
    }

    @Test
    void sendWithForcedPartitionNegativeUsesZero() throws Exception {
        RedisMessageProducer p = producer(null);
        Message m = new Message();
        m.setTopic("t");
        m.setPayload("pay");
        m.setHeaders(new HashMap<>());
        m.getHeaders().put(MqHeaders.FORCE_PARTITION_ID, "-3");
        String out = p.send(m).get();
        assertEquals(id.toString(), out);
    }

    @Test
    void sendFailureWrapsException() {
        RedisMessageProducer p = producer(null);
        when(stream.add(any(org.redisson.api.stream.StreamAddArgs.class)))
                .thenThrow(new IllegalStateException("xadd failed"));
        Message m = new Message();
        m.setTopic("t");
        m.setPayload("pay");
        ExecutionException e = assertThrows(ExecutionException.class, () -> p.send(m).get());
        assertTrue(e.getCause().getMessage().contains("Failed to send message"));
    }
}
