package io.github.cuihairu.redis.streaming.sink.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the half of {@link KafkaSink} the happy-path suite in {@link KafkaSinkTest}
 * never reaches: the three public constructors (which are the only places a real
 * {@link org.apache.kafka.clients.producer.KafkaProducer} is built) and the failure arms of the
 * three write methods.
 *
 * <p>No broker is needed. A {@code KafkaProducer} is configured and its sender thread started at
 * construction time, but it neither connects nor blocks until a record is handed to it, so the
 * tests that construct one keep {@code max.block.ms} tiny and address a port nothing listens on
 * ({@code 127.0.0.1:1}) — the resulting {@code Connection refused}/{@code TimeoutException} is
 * exactly the error path being asserted.</p>
 */
class KafkaSinkProducerLifecycleTest {

    /** Nothing listens here; any attempt to reach a broker fails immediately. */
    private static final String DEAD_BROKER = "127.0.0.1:1";

    private static Properties fastFailProperties() {
        Properties props = KafkaSink.buildDefaultProducerProperties(DEAD_BROKER);
        props.put("max.block.ms", "1000");
        props.put("request.timeout.ms", "1000");
        props.put("delivery.timeout.ms", "2000");
        return props;
    }

    @SuppressWarnings("unchecked")
    private static Producer<String, String> mockProducer() {
        return (Producer<String, String>) mock(Producer.class);
    }

    /** Walks the cause chain looking for a marker so the assertion survives Jackson's wrapping. */
    private static boolean causeChainContains(Throwable t, String marker) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains(marker)) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    /* ---------- producer properties ---------- */

    @Test
    void defaultProducerPropertiesCoverTheWholeTuningSet() {
        Properties props = KafkaSink.buildDefaultProducerProperties("broker-1:9092,broker-2:9092");

        assertEquals(8, props.size(), () -> "unexpected property set: " + props);
        assertEquals("broker-1:9092,broker-2:9092", props.getProperty("bootstrap.servers"));
        assertEquals(StringSerializer.class.getName(), props.getProperty("key.serializer"));
        assertEquals(StringSerializer.class.getName(), props.getProperty("value.serializer"));
        // acks=1 + retries=3 is the "at least once, leader-acked" default of the sink.
        // Note the value types: acks is a String while the numeric tunables are Integers — the Kafka
        // config parser accepts both, and the real-producer tests below depend on that.
        assertEquals("1", props.get(ProducerConfig.ACKS_CONFIG));
        assertEquals(3, props.get(ProducerConfig.RETRIES_CONFIG));
        // batching defaults: small linger for latency, 16 KiB batches, snappy on the wire
        assertEquals(10, props.get(ProducerConfig.LINGER_MS_CONFIG));
        assertEquals(16384, props.get(ProducerConfig.BATCH_SIZE_CONFIG));
        assertEquals("snappy", props.get(ProducerConfig.COMPRESSION_TYPE_CONFIG));
    }

    @Test
    void defaultProducerPropertiesRejectNullBootstrapServers() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> KafkaSink.buildDefaultProducerProperties(null));
        assertEquals("Bootstrap servers cannot be null", ex.getMessage());
    }

    /* ---------- the three public constructors build a real producer ---------- */

    @Test
    void constructorFromBootstrapServersBuildsAProducerForItsTopic() {
        KafkaSink<String> sink = new KafkaSink<>(DEAD_BROKER, "ctor-bootstrap");
        try {
            assertEquals("ctor-bootstrap", sink.getTopic());
        } finally {
            assertDoesNotThrow(sink::close);
        }
    }

    @Test
    void constructorFromBootstrapServersAndExtractorBuildsAProducerToo() {
        KafkaSink<String> sink =
                new KafkaSink<>(DEAD_BROKER, "ctor-extractor", new ObjectMapper(), value -> "k-" + value);
        try {
            assertEquals("ctor-extractor", sink.getTopic());
            assertDoesNotThrow(sink::flush, "flush on an idle producer is a no-op");
        } finally {
            assertDoesNotThrow(sink::close);
        }
    }

    @Test
    void constructorFromCustomPropertiesRejectsEveryNullArgument() {
        Properties props = KafkaSink.buildDefaultProducerProperties(DEAD_BROKER);
        ObjectMapper mapper = new ObjectMapper();

        NullPointerException propsEx = assertThrows(NullPointerException.class,
                () -> new KafkaSink<String>((Properties) null, "t", mapper, null));
        assertEquals("Producer properties cannot be null", propsEx.getMessage());

        NullPointerException topicEx = assertThrows(NullPointerException.class,
                () -> new KafkaSink<String>(props, null, mapper, null));
        assertEquals("Topic cannot be null", topicEx.getMessage());

        NullPointerException mapperEx = assertThrows(NullPointerException.class,
                () -> new KafkaSink<String>(props, "t", null, null));
        assertEquals("ObjectMapper cannot be null", mapperEx.getMessage());
    }

    @Test
    void constructorFromCustomPropertiesHandsThemToKafkaForValidation() {
        Properties props = KafkaSink.buildDefaultProducerProperties(DEAD_BROKER);
        props.put("acks", "nonsense");

        // the sink does not re-implement the Kafka config contract: an invalid value is rejected by
        // the producer itself, which proves the properties really reach the client
        ConfigException ex = assertThrows(ConfigException.class,
                () -> new KafkaSink<String>(props, "t", new ObjectMapper(), null));
        assertTrue(ex.getMessage().contains("acks"), ex::getMessage);
    }

    /**
     * The sink exposes no transaction control ({@code initTransactions}/{@code beginTransaction}),
     * so a producer configured with a {@code transactional.id} stays in the UNINITIALIZED state and
     * every write fails with the producer's own transition error, wrapped by the sink.
     */
    @Test
    void transactionalProducerConfigIsAcceptedButWriteNeedsAnInitializedTransaction() {
        Properties props = fastFailProperties();
        props.put("transactional.id", "kafka-sink-tx-1");
        props.put("enable.idempotence", "true");
        props.put("acks", "all");

        KafkaSink<String> sink = new KafkaSink<String>(props, "tx-topic", new ObjectMapper(), null);
        try {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> sink.write("v"));
            assertEquals("Failed to write to Kafka", ex.getMessage());
            IllegalStateException cause = assertInstanceOf(IllegalStateException.class, ex.getCause(),
                    "the transaction state error is surfaced verbatim, not swallowed");
            assertTrue(causeChainContains(cause, "kafka-sink-tx-1"), cause::getMessage);
        } finally {
            assertDoesNotThrow(sink::close);
        }
    }

    /**
     * The real producer, no broker anywhere: the metadata wait is bounded by {@code max.block.ms},
     * so the write fails fast with the wrapped timeout instead of hanging on retries.
     */
    @Test
    void writeAgainstAnUnreachableBrokerFailsFastWithTheWrappedTimeout() {
        KafkaSink<String> sink =
                new KafkaSink<String>(fastFailProperties(), "no-broker-topic", new ObjectMapper(), null);
        try {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> sink.write("v"));
            assertEquals("Failed to write to Kafka", ex.getMessage());
            // the send itself is accepted (the record is buffered locally); what fails is the bounded
            // metadata wait inside future.get(), so the client error arrives wrapped
            assertInstanceOf(ExecutionException.class, ex.getCause(), () -> "unexpected cause: " + ex.getCause());
            org.apache.kafka.common.errors.TimeoutException kafkaTimeout =
                    assertInstanceOf(org.apache.kafka.common.errors.TimeoutException.class, ex.getCause().getCause());
            assertTrue(kafkaTimeout.getMessage().contains("not present in metadata"), kafkaTimeout::getMessage);
        } finally {
            assertDoesNotThrow(sink::close);
        }
    }

    /* ---------- failure arms of the write methods ---------- */

    @Test
    void writeAsyncCompletesExceptionallyWhenTheProducerRejectsTheRecordSynchronously() {
        Producer<String, String> producer = mockProducer();
        doThrow(new IllegalStateException("producer closed")).when(producer)
                .send(any(ProducerRecord.class), any(Callback.class));

        KafkaSink<String> sink = new KafkaSink<>(producer, "t", new ObjectMapper(), null);
        CompletableFuture<org.apache.kafka.clients.producer.RecordMetadata> future = sink.writeAsync("v");

        assertTrue(future.isCompletedExceptionally());
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertEquals("producer closed", ex.getCause().getMessage());
    }

    @Test
    void writeToPartitionWrapsProducerSendFailures() {
        Producer<String, String> producer = mockProducer();
        when(producer.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("send refused"));

        KafkaSink<String> sink = new KafkaSink<>(producer, "t", new ObjectMapper(), null);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> sink.writeToPartition("v", 1));

        assertEquals("Failed to write to Kafka", ex.getMessage());
        assertEquals("send refused", ex.getCause().getMessage());
    }

    @Test
    void writeToPartitionWrapsFailedFuturesComingBackFromTheBroker() {
        Producer<String, String> producer = mockProducer();
        java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> failed =
                CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException("no acks"));
        when(producer.send(any(ProducerRecord.class))).thenReturn(failed);

        KafkaSink<String> sink = new KafkaSink<>(producer, "t", new ObjectMapper(), null);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> sink.writeToPartition("v", 1));

        assertEquals("Failed to write to Kafka", ex.getMessage());
        // future.get() surfaces the broker error as an ExecutionException, which the sink wraps
        assertInstanceOf(ExecutionException.class, ex.getCause());
        assertEquals("no acks", ex.getCause().getCause().getMessage());
    }

    @Test
    void writeToPartitionRejectsNullElements() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<String> sink = new KafkaSink<>(producer, "t", new ObjectMapper(), null);
        try {
            assertThrows(NullPointerException.class, () -> sink.writeToPartition(null, 0));
        } finally {
            sink.close();
        }
    }

    /* ---------- key extraction and serialization failures ---------- */

    @Test
    void writeWrapsKeyExtractorFailures() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<String> sink = new KafkaSink<>(producer, "t", new ObjectMapper(), value -> {
            throw new IllegalStateException("no key for " + value);
        });
        try {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> sink.write("v"));
            assertEquals("Failed to write to Kafka", ex.getMessage());
            assertEquals("no key for v", ex.getCause().getMessage());
            assertEquals(0, producer.history().size(), "nothing may reach the broker after a key failure");
        } finally {
            sink.close();
        }
    }

    /** A bean whose accessor blows up is the canonical "Jackson cannot serialize this" element. */
    static class ExplodingBean {
        public String getBoom() {
            throw new IllegalStateException("boom-in-accessor");
        }
    }

    @Test
    void writeWrapsSerializationFailuresOfNonStringElements() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<ExplodingBean> sink = new KafkaSink<>(producer, "t", new ObjectMapper(), null);
        try {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> sink.write(new ExplodingBean()));
            assertEquals("Failed to write to Kafka", ex.getMessage());
            assertTrue(causeChainContains(ex, "boom-in-accessor"),
                    "the accessor failure must survive the Jackson wrapping: " + ex.getCause());
            assertEquals(0, producer.history().size());
        } finally {
            sink.close();
        }
    }

    @Test
    void writeAsyncCompletesExceptionallyWhenSerializationFails() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<ExplodingBean> sink = new KafkaSink<>(producer, "t", new ObjectMapper(), null);
        try {
            CompletableFuture<org.apache.kafka.clients.producer.RecordMetadata> future =
                    sink.writeAsync(new ExplodingBean());
            assertTrue(future.isCompletedExceptionally());
            ExecutionException ex = assertThrows(ExecutionException.class, future::get);
            assertTrue(causeChainContains(ex, "boom-in-accessor"),
                    "the accessor failure must survive the Jackson wrapping: " + ex.getCause());
            assertEquals(0, producer.history().size());
        } finally {
            sink.close();
        }
    }

    /* ---------- async happy paths the first suite only covered without a key ---------- */

    @Test
    void writeAsyncDeliversTheExtractedKey() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<String> sink = new KafkaSink<>(producer, "async-key", new ObjectMapper(), v -> "k-" + v);
        try {
            sink.writeAsync("v").join();

            List<ProducerRecord<String, String>> history = producer.history();
            assertEquals(1, history.size());
            assertEquals("async-key", history.get(0).topic());
            assertEquals("k-v", history.get(0).key());
            assertEquals("v", history.get(0).value());
        } finally {
            sink.close();
        }
    }

    @Test
    void writeAsyncSerializesNonStringElementsToJson() {
        record Event(String name, int count) {
        }

        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<Event> sink = new KafkaSink<>(producer, "t", new ObjectMapper(), e -> e.name());
        try {
            sink.writeAsync(new Event("widget", 3)).join();

            ProducerRecord<String, String> record = producer.history().get(0);
            assertEquals("widget", record.key());
            assertEquals("{\"name\":\"widget\",\"count\":3}", record.value());
        } finally {
            sink.close();
        }
    }

    /* ---------- close() ---------- */

    @Test
    void closeClosesTheUnderlyingProducerOnEveryCall() {
        Producer<String, String> producer = mockProducer();
        KafkaSink<String> sink = new KafkaSink<>(producer, "t", new ObjectMapper(), null);

        sink.close();
        sink.close();

        // no guard: every close() is delegated, and the `producer != null` false arm of
        // KafkaSink#close is a defensive ceiling (every constructor rejects a null producer)
        verify(producer, times(2)).close();
    }

    @Test
    void writeAfterCloseStillReachesTheProducerSoFailuresSurfaceFromTheClient() {
        Producer<String, String> producer = mockProducer();
        when(producer.send(any(ProducerRecord.class)))
                .thenThrow(new IllegalStateException("this producer has been closed"));
        KafkaSink<String> sink = new KafkaSink<>(producer, "t", new ObjectMapper(), null);
        sink.close();

        RuntimeException ex = assertThrows(RuntimeException.class, () -> sink.write("v"));
        assertEquals("Failed to write to Kafka", ex.getMessage());
        assertEquals("this producer has been closed", ex.getCause().getMessage());
    }

    @Test
    void getTopicIsAvailableBeforeAnyWrite() {
        Producer<String, String> producer = mockProducer();
        KafkaSink<String> sink = new KafkaSink<>(producer, "topic-only", new ObjectMapper(), null);
        assertEquals("topic-only", sink.getTopic());
        assertNull(new MockProducer<String, String>(true, new StringSerializer(), new StringSerializer())
                .history().stream().findFirst().orElse(null));
    }
}
