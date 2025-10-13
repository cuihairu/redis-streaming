package io.github.cuihairu.redis.streaming.registry;

/**
 * 消息中间件协议枚举
 * 定义基于消息中间件的服务通信协议
 */
public enum MessagingProtocol implements Protocol {
    REDIS_STREAM("redis-stream", false, 6379, "Redis Stream Based Service"),
    REDIS_STREAM_TLS("redis-stream-tls", true, 6380, "Redis Stream over TLS"),
    REDIS_PUBSUB("redis-pubsub", false, 6379, "Redis Pub/Sub Messaging"),
    REDIS_PUBSUB_TLS("redis-pubsub-tls", true, 6380, "Redis Pub/Sub over TLS"),
    KAFKA("kafka", false, 9092, "Apache Kafka Messaging"),
    KAFKA_TLS("kafka-tls", true, 9093, "Apache Kafka over TLS"),
    PULSAR("pulsar", false, 6650, "Apache Pulsar Messaging"),
    PULSAR_TLS("pulsar-tls", true, 6651, "Apache Pulsar over TLS"),
    RABBITMQ("rabbitmq", false, 5672, "RabbitMQ AMQP Messaging"),
    RABBITMQ_TLS("rabbitmq-tls", true, 5671, "RabbitMQ AMQP over TLS"),
    NATS("nats", false, 4222, "NATS Messaging"),
    NATS_TLS("nats-tls", true, 4223, "NATS over TLS"),
    MQTT("mqtt", false, 1883, "MQTT Protocol"),
    MQTTS("mqtts", true, 8883, "MQTT over TLS");

    private final String name;
    private final boolean secure;
    private final int defaultPort;
    private final String description;

    MessagingProtocol(String name, boolean secure, int defaultPort, String description) {
        this.name = name;
        this.secure = secure;
        this.defaultPort = defaultPort;
        this.description = description;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public int getDefaultPort() {
        return defaultPort;
    }

    @Override
    public String getDescription() {
        return description;
    }

    /**
     * 根据协议名称获取协议枚举
     */
    public static MessagingProtocol fromName(String name) {
        for (MessagingProtocol protocol : values()) {
            if (protocol.getName().equalsIgnoreCase(name)) {
                return protocol;
            }
        }
        throw new IllegalArgumentException("Unknown messaging protocol: " + name);
    }

    /**
     * 根据是否安全获取 Redis Stream 协议
     */
    public static MessagingProtocol redisStream(boolean secure) {
        return secure ? REDIS_STREAM_TLS : REDIS_STREAM;
    }

    /**
     * 根据是否安全获取 Redis Pub/Sub 协议
     */
    public static MessagingProtocol redisPubSub(boolean secure) {
        return secure ? REDIS_PUBSUB_TLS : REDIS_PUBSUB;
    }

    /**
     * 根据是否安全获取 Kafka 协议
     */
    public static MessagingProtocol kafka(boolean secure) {
        return secure ? KAFKA_TLS : KAFKA;
    }

    /**
     * 根据是否安全获取 RabbitMQ 协议
     */
    public static MessagingProtocol rabbitmq(boolean secure) {
        return secure ? RABBITMQ_TLS : RABBITMQ;
    }

    /**
     * 根据是否安全获取 Pulsar 协议
     */
    public static MessagingProtocol pulsar(boolean secure) {
        return secure ? PULSAR_TLS : PULSAR;
    }

    /**
     * 根据是否安全获取 NATS 协议
     */
    public static MessagingProtocol nats(boolean secure) {
        return secure ? NATS_TLS : NATS;
    }

    /**
     * 根据是否安全获取 MQTT 协议
     */
    public static MessagingProtocol mqtt(boolean secure) {
        return secure ? MQTTS : MQTT;
    }
}
