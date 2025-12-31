package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedisBrokerPersistence
 */
class RedisBrokerPersistenceTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void appendReturnsNullForNullOrBlankTopicOrNullMessage() {
        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, MqOptions.builder()
                .retentionMaxLenPerPartition(0)
                .build());

        Message m = new Message();
        m.setTopic("t");
        m.setPayload("p");

        assertNull(persistence.append(null, 0, m));
        assertNull(persistence.append("   ", 0, m));
        assertNull(persistence.append("t", 0, null));
        verifyNoInteractions(mockRedissonClient);
    }

    @Test
    void appendUsesLuaXaddWhenMaxLenConfigured() {
        MqOptions options = MqOptions.builder()
                .retentionMaxLenPerPartition(10)
                .build();

        RScript script = mock(RScript.class);
        when(mockRedissonClient.getScript()).thenReturn(script);
        doReturn("123-0").when(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList(), any());

        @SuppressWarnings("unchecked")
        RSet<String> registry = mock(RSet.class);
        when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RSet) registry);
        when(registry.add(anyString())).thenReturn(true);

        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, options);

        Message m = new Message();
        m.setTopic("t1");
        m.setKey("k1");
        m.setPayload("p1");
        m.setTimestamp(Instant.parse("2025-01-01T00:00:00Z"));

        String id = persistence.append("t1", 0, m);
        assertEquals("123-0", id);

        verify(mockRedissonClient, never()).getStream(anyString(), any());
    }

    @Test
    void appendFallsBackToStreamAddWhenMaxLenIsZero() {
        MqOptions options = MqOptions.builder()
                .retentionMaxLenPerPartition(0)
                .build();

        @SuppressWarnings("unchecked")
        RSet<String> registry = mock(RSet.class);
        when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RSet) registry);
        when(registry.add(anyString())).thenReturn(true);

        @SuppressWarnings("unchecked")
        RStream<String, Object> stream = mock(RStream.class);
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RStream) stream);
        when(stream.add(any())).thenReturn(new StreamMessageId(1000, 1));

        BrokerPersistence persistence = new RedisBrokerPersistence(mockRedissonClient, options);

        Message m = new Message();
        m.setTopic("t1");
        m.setPayload("p1");

        String id = persistence.append("t1", 2, m);
        assertEquals("1000-1", id);
        verify(stream).add(any());
    }
}
