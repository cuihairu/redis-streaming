package io.github.cuihairu.redis.streaming.cdc.mq;

import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Bridge that forwards CDC {@link ChangeEvent}s onto a redis-streaming MQ topic.
 *
 * <p>Each event is published as a self-describing payload map (event type, database, table,
 * key, before/after images and timestamp), using the event key as the MQ partition key so
 * changes to the same row stay ordered within a partition. The send is performed
 * synchronously (bounded by {@code sendTimeoutSeconds}) so failures surface to the runtime
 * for retry/DLQ handling instead of being dropped.</p>
 *
 * <p>The producer is not serialized with the sink; this sink is intended for single-JVM
 * execution contexts where the producer is provided at construction time.</p>
 */
@Slf4j
public class ChangeEventQueueSink implements StreamSink<ChangeEvent> {

    private static final long serialVersionUID = 1L;

    private final transient MessageProducer producer;
    private final String topic;
    private final long sendTimeoutSeconds;

    public ChangeEventQueueSink(MessageProducer producer, String topic) {
        this(producer, topic, 10L);
    }

    public ChangeEventQueueSink(MessageProducer producer, String topic, long sendTimeoutSeconds) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.topic = Objects.requireNonNull(topic, "topic");
        if (sendTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("sendTimeoutSeconds must be > 0");
        }
        this.sendTimeoutSeconds = sendTimeoutSeconds;
    }

    @Override
    public void invoke(ChangeEvent event) throws Exception {
        if (event == null) {
            return;
        }
        Map<String, Object> payload = toPayload(event);
        producer.send(topic, event.getKey(), payload).get(sendTimeoutSeconds, TimeUnit.SECONDS);
    }

    static Map<String, Object> toPayload(ChangeEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", event.getEventType() == null ? null : event.getEventType().name());
        payload.put("database", event.getDatabase());
        payload.put("table", event.getTable());
        payload.put("key", event.getKey());
        if (event.getBeforeData() != null) {
            payload.put("before", new HashMap<>(event.getBeforeData()));
        }
        if (event.getAfterData() != null) {
            payload.put("after", new HashMap<>(event.getAfterData()));
        }
        payload.put("timestamp", (event.getTimestamp() == null ? Instant.now() : event.getTimestamp()).toString());
        return payload;
    }
}
