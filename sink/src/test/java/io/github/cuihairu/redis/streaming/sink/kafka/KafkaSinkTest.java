package io.github.cuihairu.redis.streaming.sink.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
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
}
