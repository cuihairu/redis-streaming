package io.github.cuihairu.redis.streaming.sink.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Redis List Sink for writing data to Redis Lists.
 * Uses Redis List as a simplified stream implementation.
 *
 * @param <T> the type of elements to write
 */
@Slf4j
public class RedisStreamSink<T> {

    private final RedissonClient redissonClient;
    private final String listName;
    private final ObjectMapper objectMapper;

    /**
     * Create a Redis List sink.
     *
     * @param redissonClient the Redisson client
     * @param listName       the Redis List name
     */
    public RedisStreamSink(RedissonClient redissonClient, String listName) {
        this(redissonClient, listName, new ObjectMapper());
    }

    /**
     * Create a Redis List sink with custom settings.
     *
     * @param redissonClient the Redisson client
     * @param listName       the Redis List name
     * @param objectMapper   the JSON object mapper
     */
    public RedisStreamSink(
            RedissonClient redissonClient,
            String listName,
            ObjectMapper objectMapper) {
        Objects.requireNonNull(redissonClient, "RedissonClient cannot be null");
        Objects.requireNonNull(listName, "List name cannot be null");
        Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");

        this.redissonClient = redissonClient;
        this.listName = listName;
        this.objectMapper = objectMapper;
    }

    /**
     * Write an element to the Redis List (synchronous).
     *
     * @param element the element to write
     * @return true if successful
     */
    public boolean write(T element) {
        try {
            RList<String> list = redissonClient.getList(listName);

            String value;
            if (element instanceof String) {
                value = (String) element;
            } else {
                value = objectMapper.writeValueAsString(element);
            }

            boolean success = list.add(value);
            log.debug("Written to Redis List {}: {}", listName, value);
            return success;

        } catch (Exception e) {
            log.error("Failed to write to Redis List: {}", listName, e);
            throw new RuntimeException("Failed to write to Redis List", e);
        }
    }

    /**
     * Write an element to the Redis List (asynchronous).
     *
     * @param element the element to write
     * @return a CompletableFuture with the result
     */
    public CompletableFuture<Boolean> writeAsync(T element) {
        return CompletableFuture.supplyAsync(() -> write(element));
    }

    /**
     * Write multiple elements in batch.
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
     * Get the list size.
     *
     * @return the number of elements in the list
     */
    public int getSize() {
        RList<String> list = redissonClient.getList(listName);
        return list.size();
    }

    /**
     * Clear the list.
     */
    public void clear() {
        RList<String> list = redissonClient.getList(listName);
        list.clear();
        log.info("Cleared Redis List: {}", listName);
    }

    /**
     * Delete the list.
     */
    public void deleteList() {
        RList<String> list = redissonClient.getList(listName);
        list.delete();
        log.info("Deleted Redis List: {}", listName);
    }

    public String getStreamName() {
        return listName;
    }
}
