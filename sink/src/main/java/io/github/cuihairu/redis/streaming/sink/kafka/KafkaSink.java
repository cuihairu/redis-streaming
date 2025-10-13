package io.github.cuihairu.redis.streaming.sink.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Kafka Sink for writing data to Apache Kafka topics.
 * Supports both sync and async writes with optional key extraction.
 *
 * @param <T> the type of elements to write
 */
@Slf4j
public class KafkaSink<T> implements AutoCloseable {

    private final KafkaProducer<String, String> producer;
    private final String topic;
    private final ObjectMapper objectMapper;
    private final KeyExtractor<T> keyExtractor;

    /**
     * Create a Kafka sink with default settings.
     *
     * @param bootstrapServers Kafka broker addresses (e.g., "localhost:9092")
     * @param topic            the Kafka topic
     */
    public KafkaSink(String bootstrapServers, String topic) {
        this(bootstrapServers, topic, new ObjectMapper(), null);
    }

    /**
     * Create a Kafka sink with custom settings.
     *
     * @param bootstrapServers Kafka broker addresses
     * @param topic            the Kafka topic
     * @param objectMapper     the JSON object mapper
     * @param keyExtractor     function to extract key from element (null for no key)
     */
    public KafkaSink(
            String bootstrapServers,
            String topic,
            ObjectMapper objectMapper,
            KeyExtractor<T> keyExtractor) {
        Objects.requireNonNull(bootstrapServers, "Bootstrap servers cannot be null");
        Objects.requireNonNull(topic, "Topic cannot be null");
        Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");

        this.topic = topic;
        this.objectMapper = objectMapper;
        this.keyExtractor = keyExtractor;

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        this.producer = new KafkaProducer<>(props);
        log.info("Created Kafka sink for topic: {}", topic);
    }

    /**
     * Create a Kafka sink with custom producer properties.
     *
     * @param producerProps  custom Kafka producer properties
     * @param topic          the Kafka topic
     * @param objectMapper   the JSON object mapper
     * @param keyExtractor   function to extract key from element
     */
    public KafkaSink(
            Properties producerProps,
            String topic,
            ObjectMapper objectMapper,
            KeyExtractor<T> keyExtractor) {
        Objects.requireNonNull(producerProps, "Producer properties cannot be null");
        Objects.requireNonNull(topic, "Topic cannot be null");
        Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");

        this.topic = topic;
        this.objectMapper = objectMapper;
        this.keyExtractor = keyExtractor;
        this.producer = new KafkaProducer<>(producerProps);

        log.info("Created Kafka sink with custom properties for topic: {}", topic);
    }

    /**
     * Write an element to Kafka (synchronous).
     *
     * @param element the element to write
     * @return metadata about the written record
     */
    public RecordMetadata write(T element) {
        Objects.requireNonNull(element, "Element cannot be null");

        try {
            String key = keyExtractor != null ? keyExtractor.extractKey(element) : null;
            String value = serializeValue(element);

            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
            Future<RecordMetadata> future = producer.send(record);

            RecordMetadata metadata = future.get(); // Block until complete
            log.debug("Written to Kafka topic {}: partition={}, offset={}",
                    topic, metadata.partition(), metadata.offset());

            return metadata;

        } catch (Exception e) {
            log.error("Failed to write to Kafka topic: {}", topic, e);
            throw new RuntimeException("Failed to write to Kafka", e);
        }
    }

    /**
     * Write an element to Kafka (asynchronous).
     *
     * @param element the element to write
     * @return a CompletableFuture with the metadata
     */
    public CompletableFuture<RecordMetadata> writeAsync(T element) {
        Objects.requireNonNull(element, "Element cannot be null");

        CompletableFuture<RecordMetadata> future = new CompletableFuture<>();

        try {
            String key = keyExtractor != null ? keyExtractor.extractKey(element) : null;
            String value = serializeValue(element);

            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);

            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Async write to Kafka failed", exception);
                    future.completeExceptionally(exception);
                } else {
                    log.debug("Async written to Kafka topic {}: partition={}, offset={}",
                            topic, metadata.partition(), metadata.offset());
                    future.complete(metadata);
                }
            });

        } catch (Exception e) {
            log.error("Failed to initiate async write to Kafka", e);
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Write to a specific partition.
     *
     * @param element   the element to write
     * @param partition the target partition
     * @return metadata about the written record
     */
    public RecordMetadata writeToPartition(T element, int partition) {
        Objects.requireNonNull(element, "Element cannot be null");

        try {
            String key = keyExtractor != null ? keyExtractor.extractKey(element) : null;
            String value = serializeValue(element);

            ProducerRecord<String, String> record = new ProducerRecord<>(topic, partition, key, value);
            Future<RecordMetadata> future = producer.send(record);

            return future.get();

        } catch (Exception e) {
            log.error("Failed to write to Kafka partition: {}", partition, e);
            throw new RuntimeException("Failed to write to Kafka", e);
        }
    }

    /**
     * Flush all buffered records.
     */
    public void flush() {
        producer.flush();
        log.debug("Flushed Kafka producer for topic: {}", topic);
    }

    /**
     * Get the topic name.
     *
     * @return the Kafka topic name
     */
    public String getTopic() {
        return topic;
    }

    @Override
    public void close() {
        if (producer != null) {
            producer.close();
            log.info("Closed Kafka sink for topic: {}", topic);
        }
    }

    private String serializeValue(T element) throws Exception {
        if (element instanceof String) {
            return (String) element;
        }
        return objectMapper.writeValueAsString(element);
    }

    /**
     * Functional interface for extracting keys from elements.
     *
     * @param <T> the element type
     */
    @FunctionalInterface
    public interface KeyExtractor<T> {
        String extractKey(T element);
    }
}
