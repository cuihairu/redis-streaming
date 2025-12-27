package io.github.cuihairu.redis.streaming.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MessagingProtocolTest {

    @Test
    public void testFromNameCaseInsensitive() {
        assertEquals(MessagingProtocol.REDIS_STREAM, MessagingProtocol.fromName("redis-stream"));
        assertEquals(MessagingProtocol.REDIS_STREAM, MessagingProtocol.fromName("REDIS-STREAM"));
        assertEquals(MessagingProtocol.KAFKA_TLS, MessagingProtocol.fromName("Kafka-TLS"));
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

        assertEquals(MessagingProtocol.KAFKA, MessagingProtocol.kafka(false));
        assertEquals(MessagingProtocol.KAFKA_TLS, MessagingProtocol.kafka(true));

        assertEquals(MessagingProtocol.RABBITMQ, MessagingProtocol.rabbitmq(false));
        assertEquals(MessagingProtocol.RABBITMQ_TLS, MessagingProtocol.rabbitmq(true));

        assertEquals(MessagingProtocol.PULSAR, MessagingProtocol.pulsar(false));
        assertEquals(MessagingProtocol.PULSAR_TLS, MessagingProtocol.pulsar(true));

        assertEquals(MessagingProtocol.NATS, MessagingProtocol.nats(false));
        assertEquals(MessagingProtocol.NATS_TLS, MessagingProtocol.nats(true));

        assertEquals(MessagingProtocol.MQTT, MessagingProtocol.mqtt(false));
        assertEquals(MessagingProtocol.MQTTS, MessagingProtocol.mqtt(true));
    }

    @Test
    public void testProtocolFields() {
        assertEquals("kafka", MessagingProtocol.KAFKA.getName());
        assertFalse(MessagingProtocol.KAFKA.isSecure());
        assertEquals(9092, MessagingProtocol.KAFKA.getDefaultPort());
        assertEquals("Apache Kafka Messaging", MessagingProtocol.KAFKA.getDescription());

        assertEquals("kafka-tls", MessagingProtocol.KAFKA_TLS.getName());
        assertTrue(MessagingProtocol.KAFKA_TLS.isSecure());
        assertEquals(9093, MessagingProtocol.KAFKA_TLS.getDefaultPort());
    }
}

