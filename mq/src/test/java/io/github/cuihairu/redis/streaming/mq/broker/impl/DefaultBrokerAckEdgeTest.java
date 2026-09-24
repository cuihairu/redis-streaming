package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerRouter;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamGroup;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers DefaultBroker.ack policy branches, parseStreamId forms and readGroup edges.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class DefaultBrokerAckEdgeTest {

    private RedissonClient client;
    private RStream<String, Object> stream;
    private RScript script;
    private BrokerRouter router;
    private BrokerPersistence persistence;

    @BeforeEach
    void setUp() {
        client = mock(RedissonClient.class);
        stream = mock(RStream.class);
        script = mock(RScript.class);
        router = mock(BrokerRouter.class);
        persistence = mock(BrokerPersistence.class);
        when(client.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RStream) stream);
        when(client.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
    }

    private DefaultBroker broker(String ackPolicy) {
        MqOptions options = MqOptions.builder()
                .ackDeletePolicy(ackPolicy)
                .acksetTtlSec(30)
                .build();
        return new DefaultBroker(client, options, router, persistence);
    }

    // ===== parseStreamId via ack =====

    @Test
    void ackParsesAllStreamIdForms() {
        DefaultBroker b = broker("none");
        b.ack("t", "g", 0, "12-34");
        verify(stream).ack(eq("g"), eq(new StreamMessageId(12, 34)));

        b.ack("t", "g", 0, "77");
        verify(stream).ack(eq("g"), eq(new StreamMessageId(77)));

        b.ack("t", "g", 0, null);
        verify(stream, times(1)).ack(eq("g"), eq(StreamMessageId.MIN));

        b.ack("t", "g", 0, "junk");
        verify(stream, times(2)).ack(eq("g"), eq(StreamMessageId.MIN));

        b.ack("t", "g", 0, "1-2-3");
        verify(stream, times(3)).ack(eq("g"), eq(StreamMessageId.MIN));
    }

    @Test
    void ackNullPolicyDefaultsToNoneAndUppercasePolicyIsNormalized() {
        DefaultBroker nullPolicy = new DefaultBroker(client, MqOptions.builder().build(), router, persistence);
        nullPolicy.ack("t", "g", 0, "5-0");
        verify(stream).ack(eq("g"), any());
        verify(stream, never()).remove(any(StreamMessageId.class));

        DefaultBroker upper = broker("IMMEDIATE");
        upper.ack("t", "g", 0, "5-1");
        verify(stream).remove(eq(new StreamMessageId(5, 1)));
    }

    @Test
    void ackRethrowsAckFailure() {
        doThrow(new IllegalStateException("xack failed")).when(stream).ack(any(), any());
        DefaultBroker b = broker("none");
        assertThrows(RuntimeException.class, () -> b.ack("t", "g", 0, "5-0"));
    }

    @Test
    void ackImmediateSwallowsRemoveFailure() {
        doThrow(new IllegalStateException("xdel failed")).when(stream).remove(any(StreamMessageId.class));
        DefaultBroker b = broker("immediate");
        assertDoesNotThrow(() -> b.ack("t", "g", 0, "5-0"));
    }

    // ===== all-groups-ack branches =====

    @Test
    void allGroupsAckSkipsDeleteWithoutActiveLeases() {
        RSet<String> ackSet = mock(RSet.class);
        when(client.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RSet) ackSet);
        when(stream.listGroups()).thenReturn(List.of(mockGroup("g1")));
        RBucket<String> lease = mock(RBucket.class);
        when(client.getBucket(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RBucket) lease);
        when(lease.isExists()).thenReturn(false); // no live lease -> active == 0

        broker("all-groups-ack").ack("t", "g1", 0, "5-0");
        verify(ackSet).add("g1");
        verify(stream, never()).remove(any(StreamMessageId.class));
    }

    @Test
    void allGroupsAckDeletesWhenAllActiveGroupsAcked() {
        RSet<String> ackSet = mock(RSet.class);
        when(client.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RSet) ackSet);
        when(ackSet.size()).thenReturn(2);
        when(stream.listGroups()).thenReturn(List.of(mockGroup("g1"), mockGroup("g2")));
        RBucket<String> lease = mock(RBucket.class);
        when(client.getBucket(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RBucket) lease);
        when(lease.isExists()).thenReturn(true);

        broker("all-groups-ack").ack("t", "g1", 0, "5-0");
        verify(stream).remove(eq(new StreamMessageId(5, 0)));
        verify(ackSet).delete();
    }

    @Test
    void allGroupsAckSurvivesAckSetAndGroupListFailures() {
        RSet<String> ackSet = mock(RSet.class);
        when(client.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RSet) ackSet);
        doThrow(new IllegalStateException("sadd failed")).when(ackSet).add(anyString());
        RBucket<String> lease = mock(RBucket.class);
        when(client.getBucket(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RBucket) lease);
        doThrow(new IllegalStateException("expire failed")).when(lease).expire(any(java.time.Duration.class));
        when(stream.listGroups()).thenThrow(new IllegalStateException("xinfo failed"));

        assertDoesNotThrow(() -> broker("all-groups-ack").ack("t", "g1", 0, "5-0"));
        verify(stream, never()).remove(any(StreamMessageId.class));
    }

    @Test
    void allGroupsAckWithPendingAckSetDoesNotDelete() {
        RSet<String> ackSet = mock(RSet.class);
        when(client.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RSet) ackSet);
        when(ackSet.size()).thenReturn(1);
        when(stream.listGroups()).thenReturn(List.of(mockGroup("g1"), mockGroup("g2")));
        RBucket<String> lease = mock(RBucket.class);
        when(client.getBucket(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RBucket) lease);
        when(lease.isExists()).thenReturn(true);

        broker("all-groups-ack").ack("t", "g1", 0, "5-0");
        verify(stream, never()).remove(any(StreamMessageId.class));
        verify(ackSet, never()).delete();
    }

    @Test
    void allGroupsAckSwallowsDeleteFailures() {
        RSet<String> ackSet = mock(RSet.class);
        when(client.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RSet) ackSet);
        when(ackSet.size()).thenReturn(1);
        when(stream.listGroups()).thenReturn(List.of(mockGroup("g1")));
        RBucket<String> lease = mock(RBucket.class);
        when(client.getBucket(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RBucket) lease);
        when(lease.isExists()).thenReturn(true);
        doThrow(new IllegalStateException("xdel failed")).when(stream).remove(any(StreamMessageId.class));
        doThrow(new IllegalStateException("del failed")).when(ackSet).delete();

        assertDoesNotThrow(() -> broker("all-groups-ack").ack("t", "g1", 0, "5-0"));
    }

    private StreamGroup mockGroup(String name) {
        return new StreamGroup(name, 0, 0, StreamMessageId.MIN);
    }

    // ===== readGroup edges =====

    @Test
    void readGroupNormalizesNegativeTimeoutAndRetriesOnlyOnNoGroup() {
        when(stream.readGroup(eq("cg"), eq("c1"), any(StreamReadGroupArgs.class)))
                .thenReturn(Collections.emptyMap());
        DefaultBroker b = broker("none");
        assertTrue(b.readGroup("t", "cg", "c1", 0, 5, -10).isEmpty());

        when(stream.readGroup(eq("cg"), eq("c1"), any(StreamReadGroupArgs.class)))
                .thenThrow(new RuntimeException("NOGROUP no such key"));
        when(stream.readGroup(eq("cg"), eq("c1"), any(StreamReadGroupArgs.class)))
                .thenReturn(Collections.emptyMap())
                .thenThrow(new RuntimeException("NOGROUP no such key"))
                .thenReturn(Collections.emptyMap());
        assertTrue(b.readGroup("t", "cg", "c1", 0, 5, 1).isEmpty());

        when(stream.readGroup(eq("cg"), eq("c1"), any(StreamReadGroupArgs.class)))
                .thenThrow(new IllegalStateException("WRONGTYPE not a stream"));
        assertThrows(IllegalStateException.class, () -> b.readGroup("t", "cg", "c1", 0, 5, 1));
    }

    @Test
    void produceAndEnsureGroupNullGuards() {
        DefaultBroker b = broker("none");
        assertNull(b.produce(null));
        Message m = new Message();
        m.setTopic("  ");
        assertNull(b.produce(m));
    }
}
