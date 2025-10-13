package io.github.cuihairu.redis.streaming.source.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Redis List Source for reading data from Redis Lists.
 * Supports both polling and blocking pop operations.
 *
 * @param <T> the type of elements to read
 */
@Slf4j
public class RedisListSource<T> implements AutoCloseable {

    private final RedissonClient redissonClient;
    private final String listName;
    private final ObjectMapper objectMapper;
    private final Class<T> valueClass;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    /**
     * Create a Redis List source.
     *
     * @param redissonClient the Redisson client
     * @param listName       the Redis List name
     * @param valueClass     the class type of values
     */
    public RedisListSource(
            RedissonClient redissonClient,
            String listName,
            Class<T> valueClass) {
        this(redissonClient, listName, new ObjectMapper(), valueClass);
    }

    /**
     * Create a Redis List source with custom settings.
     *
     * @param redissonClient the Redisson client
     * @param listName       the Redis List name
     * @param objectMapper   the JSON object mapper
     * @param valueClass     the class type of values
     */
    public RedisListSource(
            RedissonClient redissonClient,
            String listName,
            ObjectMapper objectMapper,
            Class<T> valueClass) {
        Objects.requireNonNull(redissonClient, "RedissonClient cannot be null");
        Objects.requireNonNull(listName, "List name cannot be null");
        Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
        Objects.requireNonNull(valueClass, "Value class cannot be null");

        this.redissonClient = redissonClient;
        this.listName = listName;
        this.objectMapper = objectMapper;
        this.valueClass = valueClass;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("redis-list-poller-" + listName);
            t.setDaemon(true);
            return t;
        });

        log.info("Created Redis List source for: {}", listName);
    }

    /**
     * Read one element from the list (LPOP).
     *
     * @return the element, or null if list is empty
     */
    public T readOne() {
        try {
            RList<String> list = redissonClient.getList(listName);
            String value = list.remove(0);

            if (value == null) {
                return null;
            }

            return deserialize(value);

        } catch (IndexOutOfBoundsException e) {
            return null;
        } catch (Exception e) {
            log.error("Failed to read from Redis List: {}", listName, e);
            return null;
        }
    }

    /**
     * Read multiple elements from the list.
     *
     * @param count maximum number of elements to read
     * @return list of elements
     */
    public List<T> readBatch(int count) {
        List<T> result = new ArrayList<>();

        try {
            RList<String> list = redissonClient.getList(listName);

            for (int i = 0; i < count && !list.isEmpty(); i++) {
                String value = list.remove(0);
                if (value != null) {
                    T item = deserialize(value);
                    if (item != null) {
                        result.add(item);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to read batch from Redis List: {}", listName, e);
        }

        return result;
    }

    /**
     * Read all elements from the list.
     *
     * @return list of all elements
     */
    public List<T> readAll() {
        List<T> result = new ArrayList<>();

        try {
            RList<String> list = redissonClient.getList(listName);

            while (!list.isEmpty()) {
                String value = list.remove(0);
                if (value != null) {
                    T item = deserialize(value);
                    if (item != null) {
                        result.add(item);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to read all from Redis List: {}", listName, e);
        }

        return result;
    }

    /**
     * Start consuming elements continuously.
     *
     * @param handler the element handler
     */
    public void consume(Consumer<T> handler) {
        Objects.requireNonNull(handler, "Handler cannot be null");

        running = true;
        log.info("Starting Redis List consumer for: {}", listName);

        scheduler.execute(() -> {
            while (running) {
                try {
                    T element = readOne();
                    if (element != null) {
                        handler.accept(element);
                    } else {
                        // Sleep if list is empty
                        Thread.sleep(100);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in consumer loop", e);
                }
            }
            log.info("Redis List consumer stopped for: {}", listName);
        });
    }

    /**
     * Start polling for elements periodically.
     *
     * @param handler      the element handler
     * @param pollInterval polling interval
     */
    public void poll(Consumer<T> handler, Duration pollInterval) {
        Objects.requireNonNull(handler, "Handler cannot be null");
        Objects.requireNonNull(pollInterval, "Poll interval cannot be null");

        running = true;
        log.info("Starting Redis List polling for: {} every {}", listName, pollInterval);

        scheduler.scheduleAtFixedRate(() -> {
            if (!running) {
                return;
            }

            try {
                T element = readOne();
                if (element != null) {
                    handler.accept(element);
                }
            } catch (Exception e) {
                log.error("Error in polling handler", e);
            }
        }, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Poll for batch of elements.
     *
     * @param handler      the batch handler
     * @param batchSize    batch size
     * @param pollInterval polling interval
     */
    public void pollBatch(Consumer<List<T>> handler, int batchSize, Duration pollInterval) {
        Objects.requireNonNull(handler, "Handler cannot be null");
        Objects.requireNonNull(pollInterval, "Poll interval cannot be null");

        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be positive");
        }

        running = true;
        log.info("Starting Redis List batch polling for: {} (batch={}, interval={})",
                listName, batchSize, pollInterval);

        scheduler.scheduleAtFixedRate(() -> {
            if (!running) {
                return;
            }

            try {
                List<T> batch = readBatch(batchSize);
                if (!batch.isEmpty()) {
                    handler.accept(batch);
                }
            } catch (Exception e) {
                log.error("Error in batch polling handler", e);
            }
        }, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Stop consuming/polling.
     */
    public void stop() {
        running = false;
        log.info("Stopping Redis List source for: {}", listName);
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
     * Check if the list is empty.
     *
     * @return true if empty, false otherwise
     */
    public boolean isEmpty() {
        RList<String> list = redissonClient.getList(listName);
        return list.isEmpty();
    }

    /**
     * Get the list name.
     *
     * @return the Redis List name
     */
    public String getListName() {
        return listName;
    }

    /**
     * Check if the source is running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        stop();
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("Closed Redis List source for: {}", listName);
        }
    }

    private T deserialize(String value) {
        try {
            if (valueClass == String.class) {
                return (T) value;
            }
            return objectMapper.readValue(value, valueClass);
        } catch (Exception e) {
            log.error("Failed to deserialize value: {}", e.getMessage());
            return null;
        }
    }
}
