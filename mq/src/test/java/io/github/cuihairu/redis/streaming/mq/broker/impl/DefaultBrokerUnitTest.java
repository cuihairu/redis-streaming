package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerRecord;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerRouter;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamGroup;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DefaultBrokerUnitTest {

    @Test
    void produceReturnsNullForNullOrBlankTopic() {
        RedissonClient client = mock(RedissonClient.class);
        BrokerRouter router = mock(BrokerRouter.class);
        BrokerPersistence persistence = mock(BrokerPersistence.class);
        DefaultBroker broker = new DefaultBroker(client, MqOptions.builder().defaultPartitionCount(3).build(), router, persistence);

        assertNull(broker.produce(null));

        Message blank = new Message();
        blank.setTopic("   ");
        assertNull(broker.produce(blank));

        verifyNoInteractions(router, persistence);
    }

    @Test
    void produceRoutesAndAppendsAndSetsMessageId() {
        RedissonClient client = mock(RedissonClient.class);
        BrokerRouter router = mock(BrokerRouter.class);
        BrokerPersistence persistence = mock(BrokerPersistence.class);
        when(router.routePartition(eq("t1"), eq("k1"), anyMap(), eq(1))).thenReturn(0);
        when(persistence.append(eq("t1"), eq(0), any(Message.class))).thenReturn("10-0");

        DefaultBroker broker = new DefaultBroker(client, MqOptions.builder().defaultPartitionCount(1).build(), router, persistence);

        Message m = new Message();
        m.setTopic("t1");
        m.setKey("k1");
        m.setHeaders(Collections.singletonMap("h", "v"));
        m.setPayload("p1");

        String id = broker.produce(m);
        assertEquals("10-0", id);
        assertEquals("10-0", m.getId());

        verify(router).routePartition(eq("t1"), eq("k1"), anyMap(), eq(1));
        verify(persistence).append(eq("t1"), eq(0), any(Message.class));
    }

    @Test
    void readGroupRetriesOnNoGroup() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RStream<String, Object> stream = mock(RStream.class);
        when(client.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RStream) stream);

        RScript script = mock(RScript.class);
        when(client.getScript(org.redisson.client.codec.StringCodec.INSTANCE)).thenReturn(script);
        doReturn(1L).when(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER), anyList(), anyString());

        when(stream.readGroup(eq("cg"), eq("c1"), any(StreamReadGroupArgs.class)))
                .thenThrow(new RuntimeException("NOGROUP No such key"))
                .thenReturn(Collections.singletonMap(new StreamMessageId(1, 2), Collections.singletonMap("k", (Object) "v")));

        DefaultBroker broker = new DefaultBroker(client, MqOptions.builder().build(), (t, k, h, pc) -> 0, (t, p, m) -> "x");

        List<BrokerRecord> recs = broker.readGroup("t1", "cg", "c1", 0, 10, 0);
        assertEquals(1, recs.size());
        assertEquals("1-2", recs.get(0).getId());
        assertEquals("v", recs.get(0).getData().get("k"));

        verify(stream, times(2)).readGroup(eq("cg"), eq("c1"), any(StreamReadGroupArgs.class));
    }

    @Test
    void ackImmediateDeleteRemovesAfterAck() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RStream<String, Object> stream = mock(RStream.class);
        when(client.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RStream) stream);

        DefaultBroker broker = new DefaultBroker(client, MqOptions.builder().ackDeletePolicy("immediate").build(), (t, k, h, pc) -> 0, (t, p, m) -> "x");

        broker.ack("t1", "cg", 0, "123-0");
        verify(stream).ack(eq("cg"), eq(new StreamMessageId(123, 0)));
        verify(stream).remove(eq(new StreamMessageId(123, 0)));
    }

    @Test
    void ackAllGroupsAckDeletesWhenActiveGroupsAcked() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RStream<String, Object> stream = mock(RStream.class);
        when(client.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RStream) stream);

        StreamGroup g1 = mock(StreamGroup.class);
        when(g1.getName()).thenReturn("g1");
        StreamGroup g2 = mock(StreamGroup.class);
        when(g2.getName()).thenReturn("g2");
        when(stream.listGroups()).thenReturn(List.of(g1, g2));

        @SuppressWarnings("unchecked")
        RSet<String> ackSet = mock(RSet.class);
        when(client.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RSet) ackSet);
        when(ackSet.size()).thenReturn(1);

        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(client.getBucket(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RBucket) bucket);
        when(bucket.isExists()).thenReturn(true, false);
        when(bucket.expire(any(Duration.class))).thenReturn(true);

        DefaultBroker broker = new DefaultBroker(client, MqOptions.builder().ackDeletePolicy("all-groups-ack").acksetTtlSec(60).build(),
                (t, k, h, pc) -> 0, (t, p, m) -> "x");

        broker.ack("t1", "g1", 0, "5-1");

        verify(ackSet).add(eq("g1"));
        verify(stream).remove(eq(new StreamMessageId(5, 1)));
        verify(ackSet).delete();
    }

    @Test
    void ackInvalidMessageIdUsesMinStreamId() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RStream<String, Object> stream = mock(RStream.class);
        when(client.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RStream) stream);

        DefaultBroker broker = new DefaultBroker(client, MqOptions.builder().build(), (t, k, h, pc) -> 0, (t, p, m) -> "x");
        broker.ack("t1", "cg", 0, "bad");

        verify(stream).ack(eq("cg"), eq(StreamMessageId.MIN));
    }
}
