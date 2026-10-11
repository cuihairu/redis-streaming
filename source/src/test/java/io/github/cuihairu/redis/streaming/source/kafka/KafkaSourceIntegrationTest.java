package io.github.cuihairu.redis.streaming.source.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Broker-backed coverage for {@link KafkaSource}: the unit suites drive a mocked
 * {@code KafkaConsumer}, so the delivery contract itself (poll → deserialize, offset-commit
 * restart recovery, seek after group assignment, poisoned-record isolation) is asserted here
 * against a real broker.
 *
 * <p>Requires a Kafka broker; it is <b>skipped automatically</b> when none is reachable, so the
 * usual {@code integrationTest}/{@code check} runs stay green without it. The broker comes from
 * {@code docker-compose.test.yml} ({@code KAFKA_TEST_PORT}, default 9092); standalone trigger:</p>
 *
 * <pre>
 *   export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
 *   ./gradlew :source:integrationTest --tests "KafkaSourceIntegrationTest"
 * </pre>
 */
@Tag("integration")
class KafkaSourceIntegrationTest {

    private static final String BOOTSTRAP =
            System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");

    private static final List<String> TOPICS = new ArrayList<>();
    private static Admin admin;
    private static KafkaProducer<String, String> producer;

    /* ---------- broker plumbing ---------- */

    private static Properties adminProps() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        return props;
    }

    @BeforeAll
    static void requireBrokerAndCreateProducer() {
        try (Admin probe = Admin.create(adminProps())) {
            probe.describeCluster().clusterId().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            Assumptions.abort("no Kafka broker reachable at " + BOOTSTRAP + " (" + e + ")");
            return;
        }
        admin = Admin.create(adminProps());

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000");
        producer = new KafkaProducer<>(props);
    }

    @AfterAll
    static void dropTopics() {
        if (producer != null) {
            producer.close();
        }
        if (admin != null && !TOPICS.isEmpty()) {
            try {
                admin.deleteTopics(TOPICS).all().get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort cleanup: a leftover topic must never fail the suite
            }
            admin.close();
        }
    }

    /** Creates a single-partition topic with its leader elected (ordered asserts rely on 1 partition). */
    private static String createTopic() throws Exception {
        String topic = "kafka-source-it-" + UUID.randomUUID().toString().substring(0, 8);
        admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        TOPICS.add(topic);
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

    private static void produce(String topic, String... values) throws Exception {
        for (String value : values) {
            producer.send(new ProducerRecord<>(topic, value)).get(10, TimeUnit.SECONDS);
        }
    }

    /** Polls the source until {@code expected} elements arrived or the deadline expires. */
    private static <T> List<T> drain(KafkaSource<T> source, int expected, Duration timeout) {
        List<T> got = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && got.size() < expected) {
            for (T element : source.poll(Duration.ofMillis(200))) {
                got.add(element);
            }
        }
        return got;
    }

    /* ---------- delivery contract ---------- */

    @Test
    void pollDeliversProducedRecordsDeserializedToTheTargetType() throws Exception {
        String topic = createTopic();
        produce(topic,
                "{\"id\":\"o-1\",\"amount\":10}",
                "{\"id\":\"o-2\",\"amount\":20}",
                "{\"id\":\"o-3\",\"amount\":30}");

        KafkaSource<Order> source = new KafkaSource<>(BOOTSTRAP, "src-it-" + UUID.randomUUID(), topic, Order.class);
        try {
            List<Order> got = drain(source, 3, Duration.ofSeconds(30));

            assertEquals(3, got.size(), () -> "records seen: " + got);
            // single partition + earliest reset -> the produced order is preserved
            assertEquals("o-1", got.get(0).id());
            assertEquals("o-2", got.get(1).id());
            assertEquals("o-3", got.get(2).id());
            assertEquals(20, got.get(1).amount());
        } finally {
            source.close();
        }
    }

    @Test
    void consumeAsyncDeliversEveryRecordAndStopsCleanly() throws Exception {
        String topic = createTopic();
        produce(topic, "a", "b", "c", "d", "e");

        KafkaSource<String> source = new KafkaSource<>(BOOTSTRAP, "src-it-" + UUID.randomUUID(), topic, String.class);
        AtomicInteger received = new AtomicInteger();
        AtomicBoolean stoppedWhileRunning = new AtomicBoolean();
        try {
            Thread worker = source.consumeAsync(value -> {
                received.incrementAndGet();
                if (!source.isRunning()) {
                    stoppedWhileRunning.set(true);
                }
            });

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && received.get() < 5) {
                Thread.sleep(100);
            }
            assertEquals(5, received.get(), "every produced record must reach the handler");

            source.stop();
            worker.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(worker.isAlive(), "the consumer thread must exit after stop()");
            assertFalse(source.isRunning());
            assertFalse(stoppedWhileRunning.get(), "handler must not be invoked after the loop flagged stopped");
        } finally {
            source.close();
        }
    }

    /**
     * Offset commit + consumer restart recovery: a source in group G consumes half the records and
     * commits; a fresh source reusing G resumes from the committed offset and sees only the records
     * produced afterwards.
     */
    @Test
    void committedOffsetsSurviveConsumerRestart() throws Exception {
        String topic = createTopic();
        String group = "src-it-restart-" + UUID.randomUUID();
        produce(topic, "r-1", "r-2");

        KafkaSource<String> first = new KafkaSource<>(BOOTSTRAP, group, topic, String.class);
        try {
            List<String> got = drain(first, 2, Duration.ofSeconds(30));
            assertEquals(2, got.size(), () -> "records seen: " + got);
            first.commitSync();
        } finally {
            first.close();
        }

        // produced AFTER the first consumer committed: only these may reach the restarted one
        produce(topic, "r-3", "r-4");

        KafkaSource<String> restarted = new KafkaSource<>(BOOTSTRAP, group, topic, String.class);
        try {
            List<String> got = drain(restarted, 2, Duration.ofSeconds(30));
            assertEquals(2, got.size(), () -> "records seen: " + got);
            assertTrue(got.contains("r-3") && got.contains("r-4"),
                    () -> "restart must resume from the committed offset, got: " + got);
            assertFalse(got.contains("r-1") || got.contains("r-2"),
                    () -> "committed records must not be redelivered after restart, got: " + got);
        } finally {
            restarted.close();
        }
    }

    /** seekToBeginning requires group assignment first — against a real broker that is poll-driven. */
    @Test
    void seekToBeginningRereadsTheTopicAfterGroupAssignment() throws Exception {
        String topic = createTopic();
        produce(topic, "s-1", "s-2");

        KafkaSource<String> source = new KafkaSource<>(BOOTSTRAP, "src-it-seek-" + UUID.randomUUID(), topic, String.class);
        try {
            List<String> firstPass = drain(source, 2, Duration.ofSeconds(30));
            assertEquals(2, firstPass.size());

            source.seekToBeginning();

            List<String> secondPass = drain(source, 2, Duration.ofSeconds(30));
            assertEquals(2, secondPass.size(), () -> "seek to beginning must redeliver, got: " + secondPass);
        } finally {
            source.close();
        }
    }

    /** A poisoned record (invalid JSON for the target type) is skipped, siblings still delivered. */
    @Test
    void malformedJsonIsSkippedWithoutBreakingTheStream() throws Exception {
        String topic = createTopic();
        produce(topic, "not-json", "{\"id\":\"good\",\"amount\":7}", "{\"unclosed");

        KafkaSource<Order> source = new KafkaSource<>(BOOTSTRAP, "src-it-" + UUID.randomUUID(), topic, Order.class);
        try {
            List<Order> got = drain(source, 1, Duration.ofSeconds(30));
            assertEquals(1, got.size(), () -> "records seen: " + got);
            assertEquals("good", got.get(0).id());
        } finally {
            source.close();
        }
    }

    /** Test payload type (Jackson deserializes records natively). */
    public record Order(String id, int amount) {
    }
}
