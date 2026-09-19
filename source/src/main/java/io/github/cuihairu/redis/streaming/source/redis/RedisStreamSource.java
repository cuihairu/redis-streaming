package io.github.cuihairu.redis.streaming.source.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Redis Stream source that consumes entries via {@code XREADGROUP}.
 *
 * <p>Pairs with {@code sink.redis.RedisStreamSink} (XADD): entries are expected to carry the
 * payload as JSON in a single configurable field (defaults to {@code value}). The consumer
 * group is created on start (ignoring BUSYGROUP) and entries are acknowledged after being
 * collected.</p>
 *
 * <p>{@link #run(SourceContext)} performs a bounded drain: it stops after
 * {@code maxIdlePolls} consecutive empty reads so the source terminates on idle streams,
 * which matches the pull semantics of the in-memory engine. Long-running jobs should
 * instead embed this class' polling loop in their own driver.</p>
 *
 * <p>Like other client-holding sources in this module, the {@link RedissonClient} is not
 * serialized; the source is intended for single-JVM execution contexts.</p>
 *
 * @param <T> the payload type
 */
@Slf4j
public class RedisStreamSource<T> implements StreamSource<T> {

    private static final long serialVersionUID = 1L;

    /** Default stream entry field carrying the payload (mirrors RedisStreamSink). */
    public static final String DEFAULT_VALUE_FIELD = "value";

    private final String streamName;
    private final String consumerGroup;
    private final String consumerName;
    private final String valueField;
    private final Class<T> valueClass;
    private final int batchCount;
    private final long pollTimeoutMs;
    private final int maxIdlePolls;
    private final transient RedissonClient redissonClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisStreamSource(RedissonClient redissonClient, String streamName,
                             String consumerGroup, String consumerName, Class<T> valueClass) {
        this(redissonClient, streamName, consumerGroup, consumerName, DEFAULT_VALUE_FIELD,
                valueClass, 32, 200L, 3);
    }

    public RedisStreamSource(RedissonClient redissonClient, String streamName,
                             String consumerGroup, String consumerName, String valueField,
                             Class<T> valueClass, int batchCount, long pollTimeoutMs, int maxIdlePolls) {
        Objects.requireNonNull(redissonClient, "RedissonClient cannot be null");
        Objects.requireNonNull(streamName, "Stream name cannot be null");
        Objects.requireNonNull(consumerGroup, "Consumer group cannot be null");
        Objects.requireNonNull(consumerName, "Consumer name cannot be null");
        Objects.requireNonNull(valueField, "Value field cannot be null");
        Objects.requireNonNull(valueClass, "Value class cannot be null");
        if (batchCount < 1) {
            throw new IllegalArgumentException("batchCount must be >= 1");
        }
        if (maxIdlePolls < 1) {
            throw new IllegalArgumentException("maxIdlePolls must be >= 1");
        }
        this.redissonClient = redissonClient;
        this.streamName = streamName;
        this.consumerGroup = consumerGroup;
        this.consumerName = consumerName;
        this.valueField = valueField;
        this.valueClass = valueClass;
        this.batchCount = batchCount;
        this.pollTimeoutMs = pollTimeoutMs;
        this.maxIdlePolls = maxIdlePolls;
    }

    @Override
    public void run(SourceContext<T> ctx) throws Exception {
        RStream<String, String> stream = redissonClient.getStream(streamName, StringCodec.INSTANCE);
        try {
            // explicit 0-0 (read all history) rather than StreamMessageId.MIN ("-"), which requires Redis >= 7.0
            stream.createGroup(StreamCreateGroupArgs.name(consumerGroup).id(new StreamMessageId(0, 0)).makeStream());
        } catch (Exception e) {
            log.debug("Consumer group creation skipped for {}/{}: {}", streamName, consumerGroup, e.getMessage());
        }
        int idle = 0;
        while (!ctx.isStopped() && idle < maxIdlePolls) {
            Map<StreamMessageId, Map<String, String>> batch = stream.readGroup(
                    consumerGroup, consumerName,
                    StreamReadGroupArgs.neverDelivered().count(batchCount).timeout(Duration.ofMillis(pollTimeoutMs)));
            if (batch == null || batch.isEmpty()) {
                idle++;
                continue;
            }
            idle = 0;
            for (Map.Entry<StreamMessageId, Map<String, String>> entry : batch.entrySet()) {
                if (ctx.isStopped()) {
                    return;
                }
                Map<String, String> fields = entry.getValue();
                String raw = fields == null ? null : fields.get(valueField);
                if (raw == null) {
                    log.warn("Skipping stream entry {} in {}: field '{}' missing", entry.getKey(), streamName, valueField);
                    stream.ack(consumerGroup, entry.getKey());
                    continue;
                }
                T value = deserialize(raw);
                ctx.collectWithTimestamp(value, entry.getKey().getId0());
                stream.ack(consumerGroup, entry.getKey());
            }
        }
    }

    private T deserialize(String raw) throws Exception {
        if (valueClass == String.class) {
            return valueClass.cast(raw);
        }
        return objectMapper.readValue(raw, valueClass);
    }

    public String getStreamName() {
        return streamName;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }
}
