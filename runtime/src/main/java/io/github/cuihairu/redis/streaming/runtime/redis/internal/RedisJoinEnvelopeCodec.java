package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.cuihairu.redis.streaming.join.operator.Envelope;

/**
 * Wire codec for the Redis join topic: converts {@link Envelope}s to and from one
 * self-describing JSON document ({@code side, keyType, keyJson, timestamp, payloadType,
 * payloadJson}) so the consumer pipeline rebuilds typed envelopes regardless of which side
 * produced a record first. Mapper config and the TCCL type resolution mirror the join
 * operator's keyed-state convention, so a payload surviving the topic hop decodes exactly as
 * one surviving a checkpoint restore. The wire mapper is codec-owned (not the env's user
 * mapper) so the format stays stable regardless of environment configuration.
 */
public final class RedisJoinEnvelopeCodec {

    private static final ObjectMapper WIRE_MAPPER = new ObjectMapper()
            // payloads may carry java.time types (Instant etc.); register the jsr310 module
            // when present, mirroring the mq module's mapper setup
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private RedisJoinEnvelopeCodec() {
    }

    /** One self-describing envelope document on the join topic (public fields for Jackson). */
    static final class EnvelopeMessage {
        public String side;
        public String keyType;
        public String keyJson;
        public long timestamp;
        public String payloadType;
        public String payloadJson;
    }

    /**
     * Encode an envelope for the join topic. Rejects a null join key with a message naming
     * the offending side, mirroring the join operator's guard (a null key never receives its
     * peers; on the wire it also cannot route the record).
     */
    public static <K, L, R> String toJson(Envelope<K, L, R> envelope) {
        requireJoinKey(envelope);
        try {
            EnvelopeMessage msg = new EnvelopeMessage();
            msg.side = envelope.getSide().name();
            Object key = envelope.getJoinKey();
            msg.keyType = key.getClass().getName();
            msg.keyJson = WIRE_MAPPER.writeValueAsString(key);
            msg.timestamp = envelope.getTimestamp();
            Object payload = envelope.getSide() == Envelope.Side.LEFT
                    ? envelope.getLeft()
                    : envelope.getRight();
            if (payload != null) {
                msg.payloadType = payload.getClass().getName();
                msg.payloadJson = WIRE_MAPPER.writeValueAsString(payload);
            }
            return WIRE_MAPPER.writeValueAsString(msg);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode join envelope for the join topic", e);
        }
    }

    /** Decode one join-topic document back into a typed envelope. */
    @SuppressWarnings("unchecked") // runtime types are carried per document field
    public static <K, L, R> Envelope<K, L, R> parse(String json) {
        try {
            EnvelopeMessage msg = WIRE_MAPPER.readValue(json, EnvelopeMessage.class);
            Object key = WIRE_MAPPER.readValue(msg.keyJson, resolveType(msg.keyType));
            Object payload = msg.payloadType == null
                    ? null
                    : WIRE_MAPPER.readValue(msg.payloadJson, resolveType(msg.payloadType));
            if (Envelope.Side.RIGHT.name().equals(msg.side)) {
                return Envelope.forRight((K) key, msg.timestamp, (R) payload);
            }
            return Envelope.forLeft((K) key, msg.timestamp, (L) payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode join envelope from the join topic", e);
        }
    }

    private static Class<?> resolveType(String name) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                loader = RedisJoinEnvelopeCodec.class.getClassLoader();
            }
            return Class.forName(name, true, loader);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Cannot resolve join envelope type " + name, e);
        }
    }

    private static void requireJoinKey(Envelope<?, ?, ?> envelope) {
        if (envelope.getJoinKey() == null) {
            boolean isLeft = envelope.getSide() == Envelope.Side.LEFT;
            throw new IllegalArgumentException(
                    (isLeft ? "left" : "right") + " key selector returned null for element "
                            + (isLeft ? envelope.getLeft() : envelope.getRight())
                            + " — join keys must be non-null");
        }
    }
}
