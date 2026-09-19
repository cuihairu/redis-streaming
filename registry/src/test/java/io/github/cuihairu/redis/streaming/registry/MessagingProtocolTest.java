package io.github.cuihairu.redis.streaming.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MessagingProtocolTest {

    @Test
    public void testFromNameCaseInsensitive() {
        assertEquals(MessagingProtocol.REDIS_STREAM, MessagingProtocol.fromName("redis-stream"));
        assertEquals(MessagingProtocol.REDIS_STREAM, MessagingProtocol.fromName("REDIS-STREAM"));
        assertEquals(MessagingProtocol.REDIS_PUBSUB_TLS, MessagingProtocol.fromName("redis-pubsub-tls"));
    }

    @Test
    public void testFromNameUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> MessagingProtocol.fromName("unknown"));
    }

    @Test
    public void testFactories() {
        assertEquals(MessagingProtocol.REDIS_STREAM, MessagingProtocol.redisStream(false));
        assertEquals(MessagingProtocol.REDIS_STREAM_TLS, MessagingProtocol.redisStream(true));

        assertEquals(MessagingProtocol.REDIS_PUBSUB, MessagingProtocol.redisPubSub(false));
        assertEquals(MessagingProtocol.REDIS_PUBSUB_TLS, MessagingProtocol.redisPubSub(true));
    }

    @Test
    public void testOnlyRedisProtocolsAreSupported() {
        for (MessagingProtocol protocol : MessagingProtocol.values()) {
            assertTrue(protocol.getName().startsWith("redis-"),
                    "MessagingProtocol should only expose Redis-based protocols, found: " + protocol.getName());
        }
    }

    @Test
    public void testProtocolFields() {
        assertEquals("redis-stream", MessagingProtocol.REDIS_STREAM.getName());
        assertFalse(MessagingProtocol.REDIS_STREAM.isSecure());
        assertEquals(6379, MessagingProtocol.REDIS_STREAM.getDefaultPort());

        assertEquals("redis-stream-tls", MessagingProtocol.REDIS_STREAM_TLS.getName());
        assertTrue(MessagingProtocol.REDIS_STREAM_TLS.isSecure());
        assertEquals(6380, MessagingProtocol.REDIS_STREAM_TLS.getDefaultPort());
    }
}
