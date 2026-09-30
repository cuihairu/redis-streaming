package io.github.cuihairu.redis.streaming.sink.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Broker-backed coverage for {@link KafkaSink}: the unit suites can only prove what the sink hands
 * to the client, so the delivery contract itself (key/value round trip, partition routing, the
 * wrapped error when a partition does not exist) is asserted here against a real broker.
 *
 * <p>Requires a Kafka broker; it is <b>skipped automatically</b> when none is reachable, so the
 * usual {@code integrationTest}/{@code check} runs stay green without it. Trigger it with:</p>
 *
 * <pre>
 *   docker run -d --name kafka -p 9092:9092 \
 *     -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
 *     -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
 *     -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
 *     -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
 *     -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
 *     -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
 *     -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
 *     -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
 *     -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 apache/kafka:3.7.0
 *
 *   export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
 *   ./gradlew :sink:integrationTest --tests "KafkaSinkIntegrationTest"
 * </pre>
 */
@Tag("integration")
class KafkaSinkIntegrationTest {

    private static final String BOOTSTRAP =
            System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");

    private static final List<String> TOPICS = new ArrayList<>();
    private static Admin admin;

    /* ---------- broker plumbing ---------- */

    private static Properties adminProps() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        return props;
    }

    @BeforeAll
    static void requireBrokerAndCreateTopics() {
        try (Admin probe = Admin.create(adminProps())) {
            probe.describeCluster().clusterId().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Redisson-style eager clients do not exist here: the probe is the reachability check
            Assumptions.abort("no Kafka broker reachable at " + BOOTSTRAP + " (" + e + ")");
            return;
        }
        admin = Admin.create(adminProps());
    }

    @AfterAll
    static void dropTopics() {
        if (admin != null && !TOPICS.isEmpty()) {
            try {
                admin.deleteTopics(TOPICS).all().get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort cleanup: a leftover auto-delete topic must never fail the suite
            }
            admin.close();
        }
    }

    /** Creates a dedicated topic per test so assertions never depend on test execution order. */
    private static String createTopic(int partitions) throws Exception {
        String topic = "kafka-sink-it-" + UUID.randomUUID().toString().substring(0, 8);
        admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get(30, TimeUnit.SECONDS);
        TOPICS.add(topic);
        // wait for the leader to be elected, otherwise the first produce burns a metadata retry cycle
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            var described = admin.describeTopics(List.of(topic)).allTopicNames().get(5, TimeUnit.SECONDS);
            var partition = described.get(topic).partitions().get(0);
            if (partition.leader() != null && !partition.isr().isEmpty()) {
                return topic;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("topic " + topic + " never got a leader");
    }

    private static KafkaConsumer<String, String> consumerFor(String topic, int partitions) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-sink-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        List<TopicPartition> assigned = new ArrayList<>();
        for (int p = 0; p < partitions; p++) {
            assigned.add(new TopicPartition(topic, p));
        }
        consumer.assign(assigned);
        consumer.seekToBeginning(assigned);
        return consumer;
    }

    /** Polls until {@code expected} records arrived or the deadline expires. */
    private static List<ConsumerRecord<String, String>> drain(KafkaConsumer<String, String> consumer,
                                                             int expected, Duration timeout) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && records.size() < expected) {
            consumer.poll(Duration.ofMillis(200)).forEach(records::add);
        }
        return records;
    }

    /* ---------- delivery contract ---------- */

    @Test
    void messagesRoundTripWithTheirKeysAndJsonValues() throws Exception {
        String topic = createTopic(3);
        record Order(String id, int amount) {
        }

        KafkaSink<Object> sink = new KafkaSink<>(BOOTSTRAP, topic);
        KafkaConsumer<String, String> consumer = consumerFor(topic, 3);
        try {
            assertNotNull(sink.write("plain-string"));
            sink.write(new Order("o-1", 42));
            sink.flush();

            List<ConsumerRecord<String, String>> received = drain(consumer, 2, Duration.ofSeconds(20));
            assertEquals(2, received.size(), () -> "records seen: " + received);

            // String elements are sent verbatim (no JSON quoting), everything else is serialized
            ConsumerRecord<String, String> plain = received.stream()
                    .filter(r -> "plain-string".equals(r.value())).findFirst().orElseThrow();
            assertNull(plain.key(), "no key extractor configured -> null key");

            ConsumerRecord<String, String> json = received.stream()
                    .filter(r -> r.value().contains("\"id\"")).findFirst().orElseThrow();
            assertEquals("{\"id\":\"o-1\",\"amount\":42}", json.value());
            assertNull(json.key());
        } finally {
            consumer.close();
            sink.close();
        }
    }

    @Test
    void writeToPartitionPinsTheRecordToTheRequestedPartition() throws Exception {
        String topic = createTopic(3);
        KafkaSink<String> sink = new KafkaSink<>(BOOTSTRAP, topic, new ObjectMapper(), value -> "key-" + value);
        KafkaConsumer<String, String> consumer = consumerFor(topic, 3);
        try {
            var metadata = sink.writeToPartition("pinned", 2);
            assertEquals(2, metadata.partition());
            sink.flush();

            List<ConsumerRecord<String, String>> received = drain(consumer, 1, Duration.ofSeconds(20));
            assertEquals(1, received.size(), () -> "records seen: " + received);
            ConsumerRecord<String, String> record = received.get(0);
            assertEquals(2, record.partition(), "explicit partition routing must be honoured");
            assertEquals("key-pinned", record.key());
            assertEquals("pinned", record.value());
        } finally {
            consumer.close();
            sink.close();
        }
    }

    @Test
    void idempotentProducerConfigDeliversExactlyOnce() throws Exception {
        String topic = createTopic(1);
        Properties props = KafkaSink.buildDefaultProducerProperties(BOOTSTRAP);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        KafkaSink<String> sink = new KafkaSink<String>(props, topic, new ObjectMapper(), null);
        KafkaConsumer<String, String> consumer = consumerFor(topic, 1);
        try {
            sink.write("idempotent");
            sink.write("idempotent");
            sink.flush();

            List<ConsumerRecord<String, String>> received = drain(consumer, 2, Duration.ofSeconds(20));
            assertEquals(2, received.size(), () -> "records seen: " + received);
            // both writes carry distinct offsets: the producer did not deduplicate them away
            assertEquals(1L, received.get(1).offset() - received.get(0).offset());
        } finally {
            consumer.close();
            sink.close();
        }
    }

    /**
     * A partition that does not exist cannot be produced to: the producer retries and finally fails
     * the send. The sink must surface that as a {@link RuntimeException} instead of hanging, and
     * nothing may reach the topic.
     */
    @Test
    void writingToAnUnknownPartitionFailsWithTheWrappedProducerError() throws Exception {
        String topic = createTopic(3);
        Properties props = KafkaSink.buildDefaultProducerProperties(BOOTSTRAP);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "2000");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "2000");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "5000");

        KafkaSink<String> sink = new KafkaSink<String>(props, topic, new ObjectMapper(), null);
        KafkaConsumer<String, String> consumer = consumerFor(topic, 3);
        try {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> sink.writeToPartition("nowhere", 99));
            assertEquals("Failed to write to Kafka", ex.getMessage());
            assertInstanceOf(ExecutionException.class, ex.getCause());
            assertInstanceOf(TimeoutException.class, ex.getCause().getCause(),
                    () -> "unexpected cause: " + ex.getCause().getCause());

            assertEquals(0, drain(consumer, 1, Duration.ofSeconds(3)).size(),
                    "a failed write must not leave anything in the topic");
        } finally {
            consumer.close();
            sink.close();
        }
    }
}
