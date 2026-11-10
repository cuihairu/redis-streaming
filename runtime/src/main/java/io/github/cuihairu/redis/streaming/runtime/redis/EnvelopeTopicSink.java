package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.join.operator.Envelope;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisJoinEnvelopeCodec;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Producer-side sink for the join topic: encodes envelopes through
 * {@link RedisJoinEnvelopeCodec} and sends them through the normal MQ producer path, so
 * the join topic gets standard topic registration, partitioning and retention governance.
 * The join key doubles as the MQ key — same-key envelopes land on the same partition,
 * preserving per-key arrival order into the join operator. Send failures fail the record
 * (retry/DLQ by the runner's failure policy); a blocking send is the at-least-once backpressure.
 */
final class EnvelopeTopicSink<K, L, R> implements StreamSink<Envelope<K, L, R>> {

    private final String joinTopic;
    private final MessageQueueFactory mqFactory;
    private MessageProducer producer;

    EnvelopeTopicSink(String joinTopic, MessageQueueFactory mqFactory) {
        this.joinTopic = Objects.requireNonNull(joinTopic, "joinTopic");
        this.mqFactory = Objects.requireNonNull(mqFactory, "mqFactory");
    }

    @Override
    public void open() {
        this.producer = mqFactory.createProducer();
    }

    @Override
    public void invoke(Envelope<K, L, R> envelope) {
        // toJson rejects a null join key with a message naming the offending side —
        // propagate that IllegalArgumentException unwrapped
        String json = RedisJoinEnvelopeCodec.toJson(envelope);
        try {
            producer.send(joinTopic, String.valueOf(envelope.getJoinKey()), json)
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write join envelope to topic '" + joinTopic + "'", e);
        }
    }

    @Override
    public void close() {
        if (producer != null) {
            producer.close();
        }
    }
}
