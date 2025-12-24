package io.github.cuihairu.redis.streaming.source.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Kafka Source for reading data from Apache Kafka topics.
 * Supports both polling and streaming consumption patterns.
 *
 * @param <T> the type of elements to read
 */
@Slf4j
public class KafkaSource<T> implements AutoCloseable {

    private final org.apache.kafka.clients.consumer.Consumer<String, String> consumer;
    private final String topic;
    private final ObjectMapper objectMapper;
    private final Class<T> valueClass;
    private volatile boolean running = false;

    /**
     * Create a Kafka source with default settings.
     *
     * @param bootstrapServers Kafka broker addresses (e.g., "localhost:9092")
     * @param groupId          consumer group ID
     * @param topic            the Kafka topic to consume from
     * @param valueClass       the class type of values
     */
    public KafkaSource(
            String bootstrapServers,
            String groupId,
            String topic,
            Class<T> valueClass) {
        this(bootstrapServers, groupId, topic, new ObjectMapper(), valueClass);
    }

    /**
     * Create a Kafka source with custom settings.
     *
     * @param bootstrapServers Kafka broker addresses
     * @param groupId          consumer group ID
     * @param topic            the Kafka topic
     * @param objectMapper     the JSON object mapper
     * @param valueClass       the class type of values
     */
    public KafkaSource(
            String bootstrapServers,
            String groupId,
            String topic,
            ObjectMapper objectMapper,
            Class<T> valueClass) {
        Objects.requireNonNull(bootstrapServers, "Bootstrap servers cannot be null");
        Objects.requireNonNull(groupId, "Group ID cannot be null");
        Objects.requireNonNull(topic, "Topic cannot be null");
        Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
        Objects.requireNonNull(valueClass, "Value class cannot be null");

        this.topic = topic;
        this.objectMapper = objectMapper;
        this.valueClass = valueClass;

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");

        this.consumer = new KafkaConsumer<>(props);
        this.consumer.subscribe(Collections.singletonList(topic));

        log.info("Created Kafka source for topic: {} with group: {}", topic, groupId);
    }

    /**
     * Create a Kafka source with custom consumer properties.
     *
     * @param consumerProps  custom Kafka consumer properties
     * @param topic          the Kafka topic
     * @param objectMapper   the JSON object mapper
     * @param valueClass     the class type of values
     */
    public KafkaSource(
            Properties consumerProps,
            String topic,
            ObjectMapper objectMapper,
            Class<T> valueClass) {
        Objects.requireNonNull(consumerProps, "Consumer properties cannot be null");
        Objects.requireNonNull(topic, "Topic cannot be null");
        Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
        Objects.requireNonNull(valueClass, "Value class cannot be null");

        this.topic = topic;
        this.objectMapper = objectMapper;
        this.valueClass = valueClass;
        this.consumer = new KafkaConsumer<>(consumerProps);
        this.consumer.subscribe(Collections.singletonList(topic));

        log.info("Created Kafka source with custom properties for topic: {}", topic);
    }

    KafkaSource(
            org.apache.kafka.clients.consumer.Consumer<String, String> consumer,
            String topic,
            ObjectMapper objectMapper,
            Class<T> valueClass) {
        Objects.requireNonNull(consumer, "Consumer cannot be null");
        Objects.requireNonNull(topic, "Topic cannot be null");
        Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
        Objects.requireNonNull(valueClass, "Value class cannot be null");

        this.consumer = consumer;
        this.topic = topic;
        this.objectMapper = objectMapper;
        this.valueClass = valueClass;
    }

    /**
     * Poll for records once.
     *
     * @param timeout polling timeout
     * @return iterable of records
     */
    public Iterable<T> poll(Duration timeout) {
        ConsumerRecords<String, String> records = consumer.poll(timeout);

        List<T> result = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records.records(topic)) {
            T value = deserialize(record);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    /**
     * Start consuming messages continuously.
     *
     * @param handler the message handler
     */
    public void consume(Consumer<T> handler) {
        Objects.requireNonNull(handler, "Handler cannot be null");

        running = true;
        log.info("Starting Kafka consumer for topic: {}", topic);

        try {
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

                for (ConsumerRecord<String, String> record : records) {
                    try {
                        T value = deserialize(record);
                        if (value != null) {
                            handler.accept(value);
                        }
                    } catch (Exception e) {
                        log.error("Error processing Kafka record at offset {}: {}",
                                record.offset(), e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error in Kafka consumer loop", e);
            throw new RuntimeException("Kafka consumer error", e);
        } finally {
            log.info("Kafka consumer stopped for topic: {}", topic);
        }
    }

    /**
     * Start consuming in a separate thread.
     *
     * @param handler the message handler
     * @return the consumer thread
     */
    public Thread consumeAsync(Consumer<T> handler) {
        Thread consumerThread = new Thread(() -> consume(handler));
        consumerThread.setName("kafka-consumer-" + topic);
        consumerThread.setDaemon(true);
        consumerThread.start();
        return consumerThread;
    }

    /**
     * Stop consuming.
     */
    public void stop() {
        running = false;
        log.info("Stopping Kafka consumer for topic: {}", topic);
    }

    /**
     * Seek to the beginning of the topic.
     */
    public void seekToBeginning() {
        ensureAssigned();
        consumer.seekToBeginning(consumer.assignment());
        log.info("Seeked to beginning for topic: {}", topic);
    }

    /**
     * Seek to the end of the topic.
     */
    public void seekToEnd() {
        ensureAssigned();
        consumer.seekToEnd(consumer.assignment());
        log.info("Seeked to end for topic: {}", topic);
    }

    /**
     * Commit offsets manually.
     */
    public void commitSync() {
        consumer.commitSync();
        log.debug("Committed offsets for topic: {}", topic);
    }

    /**
     * Get the topic name.
     *
     * @return the Kafka topic name
     */
    public String getTopic() {
        return topic;
    }

    /**
     * Check if the source is running.
     *
     * @return true if consuming, false otherwise
     */
    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        stop();
        if (consumer != null) {
            consumer.close();
            log.info("Closed Kafka consumer for topic: {}", topic);
        }
    }

    private T deserialize(ConsumerRecord<String, String> record) {
        try {
            String value = record.value();
            if (value == null) {
                return null;
            }

            if (valueClass == String.class) {
                @SuppressWarnings("unchecked")
                T result = (T) value;
                return result;
            }

            return objectMapper.readValue(value, valueClass);

        } catch (Exception e) {
            log.error("Failed to deserialize Kafka record: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Kafka only assigns partitions after at least one poll. Seek operations require assignment.
     */
    private void ensureAssigned() {
        if (!consumer.assignment().isEmpty()) {
            return;
        }
        try {
            consumer.poll(Duration.ZERO);
            if (!consumer.assignment().isEmpty()) {
                return;
            }
            // Best-effort: one short poll to allow group join/assignment.
            consumer.poll(Duration.ofMillis(100));
        } catch (Exception e) {
            log.warn("Failed to ensure partition assignment for topic {} before seek", topic, e);
        }
    }
}
