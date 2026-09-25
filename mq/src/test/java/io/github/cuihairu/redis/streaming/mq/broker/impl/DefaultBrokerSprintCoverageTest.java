package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.broker.BrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerRouter;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamGroup;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link DefaultBroker}: null guards, NOGROUP retry with a
 * message-less failure, null ack policy normalization, checked ack failures and the
 * all-groups-ack cleanup failure path.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class DefaultBrokerSprintCoverageTest {

    private RedissonClient client;
    private RStream<String, Object> stream;
    private RScript script;
    private BrokerRouter router;
    private BrokerPersistence persistence;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(RedissonClient.class);
        stream = mock(RStream.class);
        script = mock(RScript.class);
        router = mock(BrokerRouter.class);
        persistence = mock(BrokerPersistence.class);
        when(client.getStream(any(), any(org.redisson.client.codec.Codec.class))).thenReturn((RStream) stream);
        when(client.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
    }

    private DefaultBroker broker(String ackPolicy) {
        MqOptions options = MqOptions.builder()
                .ackDeletePolicy(ackPolicy)
                .acksetTtlSec(30)
                .build();
        return new DefaultBroker(client, options, router, persistence);
    }

    @Test
    void readGroupRethrowsFailureWithoutMessage() throws Exception {
        when(stream.readGroup(anyString(), anyString(), any(StreamReadGroupArgs.class)))
                .thenThrow(new IllegalStateException((String) null));
        DefaultBroker b = broker("none");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> b.readGroup("t", "cg", "c1", 0, 5, 1));
        assertNull(e.getMessage());
    }

    @Test
    void ensureConsumerGroupNullGroupSkipsBootstrap() throws Exception {
        when(stream.readGroup(any(), any(), any(StreamReadGroupArgs.class))).thenReturn(java.util.Collections.emptyMap());
        DefaultBroker b = broker("none");
        assertTrue(b.readGroup("t", null, "c1", 0, 5, 1).isEmpty());
    }

    @Test
    void ensureConsumerGroupNullStreamKeySkipsBootstrap() throws Exception {
        when(stream.readGroup(any(), any(), any(StreamReadGroupArgs.class))).thenReturn(java.util.Collections.emptyMap());
        DefaultBroker b = broker("none");
        try (MockedStatic<StreamKeys> mocked = mockStatic(StreamKeys.class)) {
            mocked.when(() -> StreamKeys.partitionStream(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(null);
            assertTrue(b.readGroup("t", "cg", "c1", 0, 5, 1).isEmpty());
        }
    }

    @Test
    void ackNullPolicyBehavesAsNone() throws Exception {
        MqOptions options = mock(MqOptions.class);
        when(options.getAckDeletePolicy()).thenReturn(null);
        DefaultBroker b = new DefaultBroker(client, options, router, persistence);
        b.ack("t", "g", 0, "5-0");
        verify(stream).ack(eq("g"), eq(new StreamMessageId(5, 0)));
        verify(stream, never()).remove(any(StreamMessageId.class));
    }

    @Test
    void ackWrapsCheckedAckFailure() throws Exception {
        Exception checked = new Exception("checked xack boom");
        doAnswer(inv -> {
            throw checked;
        }).when(stream).ack(any(), any());
        DefaultBroker b = broker("none");
        RuntimeException e = assertThrows(RuntimeException.class, () -> b.ack("t", "g", 0, "5-0"));
        assertEquals("Broker ack failed", e.getMessage());
        assertEquals(checked, e.getCause());
    }

    @Test
    void allGroupsAckSwallowsAckSetDeleteFailureAfterEntryRemoval() throws Exception {
        RSet<String> ackSet = mock(RSet.class);
        when(client.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RSet) ackSet);
        when(ackSet.size()).thenReturn(1);
        when(stream.listGroups()).thenReturn(List.of(new StreamGroup("g1", 0, 0, StreamMessageId.MIN)));
        RBucket<String> lease = mock(RBucket.class);
        when(client.getBucket(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RBucket) lease);
        when(lease.isExists()).thenReturn(true);
        doThrow(new IllegalStateException("del failed")).when(ackSet).delete();

        assertDoesNotThrow(() -> broker("all-groups-ack").ack("t", "g1", 0, "5-0"));
        verify(stream).remove(eq(new StreamMessageId(5, 0)));
        verify(ackSet).delete();
    }
}
