package io.github.cuihairu.redis.streaming.sink.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class KafkaSinkTest {

    @Test
    void writeSendsRecordWithExtractedKey() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<String> sink = new KafkaSink<String>(producer, "t", new ObjectMapper(), v -> "k");
        try {
            assertNotNull(sink.write("v"));

            List<ProducerRecord<String, String>> history = producer.history();
            assertEquals(1, history.size());
            assertEquals("t", history.get(0).topic());
            assertEquals("k", history.get(0).key());
            assertEquals("v", history.get(0).value());
        } finally {
            sink.close();
        }
    }

    @Test
    void writeSerializesNonString() {
        record Event(int x) {}

        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<Event> sink = new KafkaSink<Event>(producer, "t", new ObjectMapper(), null);
        try {
            sink.write(new Event(1));
            assertTrue(producer.history().get(0).value().contains("\"x\":1"));
        } finally {
            sink.close();
        }
    }

    @Test
    void writeAsyncCompletesAndStoresRecord() throws Exception {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<String> sink = new KafkaSink<String>(producer, "t", new ObjectMapper(), null);
        try {
            assertNotNull(sink.writeAsync("v").get(3, TimeUnit.SECONDS));
            assertEquals(1, producer.history().size());
        } finally {
            sink.close();
        }
    }

    @Test
    void writeToPartitionSetsPartition() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<String> sink = new KafkaSink<String>(producer, "t", new ObjectMapper(), null);
        try {
            sink.writeToPartition("v", 2);
            assertEquals(Integer.valueOf(2), producer.history().get(0).partition());
        } finally {
            sink.close();
        }
    }

    @Test
    void writeRejectsNullElement() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<String> sink = new KafkaSink<String>(producer, "t", new ObjectMapper(), null);
        try {
            assertThrows(NullPointerException.class, () -> sink.write(null));
        } finally {
            sink.close();
        }
    }

    @Test
    void getTopicReturnsTopicName() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<String> sink = new KafkaSink<String>(producer, "test-topic", new ObjectMapper(), null);
        try {
            assertEquals("test-topic", sink.getTopic());
        } finally {
            sink.close();
        }
    }

    @Test
    void flushClearsProducerBuffer() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<String> sink = new KafkaSink<String>(producer, "t", new ObjectMapper(), null);
        try {
            sink.write("v1");
            sink.write("v2");
            sink.flush(); // Should not throw
            assertEquals(2, producer.history().size());
        } finally {
            sink.close();
        }
    }

    @Test
    void writeAsyncHandlesFailure() throws Exception {
        CompletableFuture<org.apache.kafka.clients.producer.RecordMetadata> failedFuture =
                new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Simulated failure"));

        MockProducer<String, String> producer = new MockProducer<>(false, new StringSerializer(), new StringSerializer()) {
            @Override
            public synchronized java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> send(ProducerRecord<String, String> record) {
                // Simulate failure
                return failedFuture;
            }
        };

        KafkaSink<String> sink = new KafkaSink<String>(producer, "t", new ObjectMapper(), null);
        try {
            var future = sink.writeAsync("v");
            assertThrows(Exception.class, () -> future.get(3, TimeUnit.SECONDS));
        } finally {
            sink.close();
        }
    }

    @Test
    void writeToPartitionWithKey() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<String> sink = new KafkaSink<String>(producer, "t", new ObjectMapper(), v -> "key-" + v);
        try {
            sink.writeToPartition("value", 3);
            List<ProducerRecord<String, String>> history = producer.history();
            assertEquals(1, history.size());
            assertEquals(3, history.get(0).partition());
            assertEquals("key-value", history.get(0).key());
            assertEquals("value", history.get(0).value());
        } finally {
            sink.close();
        }
    }

    @Test
    void writeWithNullKeyExtractor() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<String> sink = new KafkaSink<String>(producer, "t", new ObjectMapper(), null);
        try {
            sink.write("value");
            assertNull(producer.history().get(0).key());
            assertEquals("value", producer.history().get(0).value());
        } finally {
            sink.close();
        }
    }

    @Test
    void writeWithCustomKeyExtractor() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<String> sink = new KafkaSink<String>(producer, "t", new ObjectMapper(), v -> v.toUpperCase());
        try {
            sink.write("test");
            assertEquals("TEST", producer.history().get(0).key());
        } finally {
            sink.close();
        }
    }

    @Test
    void writeAsyncWithNullElement() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaSink<String> sink = new KafkaSink<String>(producer, "t", new ObjectMapper(), null);
        try {
            assertThrows(NullPointerException.class, () -> sink.writeAsync(null));
        } finally {
            sink.close();
        }
    }
}
