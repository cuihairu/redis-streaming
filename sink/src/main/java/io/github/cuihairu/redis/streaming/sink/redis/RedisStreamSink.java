package io.github.cuihairu.redis.streaming.sink.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.StringCodec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Redis Stream Sink that appends elements to a Redis Stream using {@code XADD}.
 *
 * <p>Each element is stored as a stream entry with a single configurable value field
 * (defaults to {@code value}); non-string elements are serialized to JSON. Use
 * {@link RedisListSink} when plain Redis Lists (RPUSH semantics) are intended.</p>
 *
 * @param <T> the type of elements to write
 */
@Slf4j
public class RedisStreamSink<T> implements StreamSink<T> {

    /** Default stream entry field name used to store the payload. */
    public static final String DEFAULT_VALUE_FIELD = "value";

    private final RedissonClient redissonClient;
    private final String streamName;
    private final String valueField;
    private final ObjectMapper objectMapper;

    /**
     * Create a Redis Stream sink writing to the default {@code value} field.
     *
     * @param redissonClient the Redisson client
     * @param streamName     the Redis Stream name
     */
    public RedisStreamSink(RedissonClient redissonClient, String streamName) {
        this(redissonClient, streamName, DEFAULT_VALUE_FIELD, new ObjectMapper());
    }

    /**
     * Create a Redis Stream sink.
     *
     * @param redissonClient the Redisson client
     * @param streamName     the Redis Stream name
     * @param valueField     the stream entry field holding the payload
     * @param objectMapper   the JSON object mapper
     */
    public RedisStreamSink(
            RedissonClient redissonClient,
            String streamName,
            String valueField,
            ObjectMapper objectMapper) {
        Objects.requireNonNull(redissonClient, "RedissonClient cannot be null");
        Objects.requireNonNull(streamName, "Stream name cannot be null");
        Objects.requireNonNull(valueField, "Value field cannot be null");
        Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");

        this.redissonClient = redissonClient;
        this.streamName = streamName;
        this.valueField = valueField;
        this.objectMapper = objectMapper;
    }

    /**
     * Append an element to the Redis Stream (synchronous XADD).
     *
     * @param element the element to write
     * @return true if the entry was added
     */
    public boolean write(T element) {
        try {
            RStream<String, String> stream = redissonClient.getStream(streamName, StringCodec.INSTANCE);

            Map<String, String> entry = new LinkedHashMap<>(2);
            if (element instanceof String) {
                entry.put(valueField, (String) element);
            } else {
                entry.put(valueField, objectMapper.writeValueAsString(element));
            }

            StreamMessageId id = stream.add(StreamAddArgs.entries(entry));
            boolean success = id != null;
            log.debug("Appended to Redis Stream {}: {}", streamName, entry);
            return success;

        } catch (Exception e) {
            log.error("Failed to append to Redis Stream: {}", streamName, e);
            throw new RuntimeException("Failed to append to Redis Stream", e);
        }
    }

    @Override
    public void invoke(T value) throws Exception {
        write(value);
    }

    /**
     * Append an element to the Redis Stream (asynchronous).
     *
     * @param element the element to write
     * @return a CompletableFuture with the result
     */
    public CompletableFuture<Boolean> writeAsync(T element) {
        return CompletableFuture.supplyAsync(() -> write(element));
    }

    /**
     * Append multiple elements in batch.
     *
     * @param elements the elements to write
     * @return number of elements written
     */
    public int writeBatch(Iterable<T> elements) {
        Objects.requireNonNull(elements, "Elements cannot be null");

        int count = 0;
        for (T element : elements) {
            if (write(element)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get the stream length (XLEN).
     *
     * @return the number of entries in the stream
     */
    public long getSize() {
        return redissonClient.<String, String>getStream(streamName, StringCodec.INSTANCE).size();
    }

    /**
     * Delete the stream (equivalent of clearing all entries).
     */
    public void clear() {
        redissonClient.getStream(streamName, StringCodec.INSTANCE).delete();
        log.info("Deleted Redis Stream: {}", streamName);
    }

    public String getStreamName() {
        return streamName;
    }

    public String getValueField() {
        return valueField;
    }
}
