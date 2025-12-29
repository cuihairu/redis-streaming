package io.github.cuihairu.redis.streaming.source.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KafkaSourceTest {

    @Test
    void pollReturnsDeserializedStringValues() {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition("t", 0);
        consumer.assign(List.of(tp));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        consumer.addRecord(new ConsumerRecord<>("t", 0, 0L, "k", "v"));

        KafkaSource<String> source = new KafkaSource<String>(consumer, "t", new ObjectMapper(), String.class);
        try {
            List<String> out = toList(source.poll(Duration.ZERO));
            assertEquals(List.of("v"), out);
        } finally {
            source.close();
        }
    }

    @Test
    void pollSkipsInvalidJsonRecords() {
        record Event(int x) {}

        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition("t", 0);
        consumer.assign(List.of(tp));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        consumer.addRecord(new ConsumerRecord<>("t", 0, 0L, "k", "not-json"));
        consumer.addRecord(new ConsumerRecord<>("t", 0, 1L, "k", "{\"x\":1}"));

        KafkaSource<Event> source = new KafkaSource<Event>(consumer, "t", new ObjectMapper(), Event.class);
        try {
            List<Event> out = toList(source.poll(Duration.ZERO));
            assertEquals(1, out.size());
            assertEquals(1, out.get(0).x());
        } finally {
            source.close();
        }
    }

    @Test
    void seekAndCommitDoNotThrowWhenAssigned() {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition("t", 0);
        consumer.assign(List.of(tp));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        consumer.updateEndOffsets(Map.of(tp, 0L));

        KafkaSource<String> source = new KafkaSource<String>(consumer, "t", new ObjectMapper(), String.class);
        try {
            source.seekToBeginning();
            source.seekToEnd();
            source.commitSync();
        } finally {
            source.close();
        }
    }

    private static <T> List<T> toList(Iterable<T> values) {
        if (values instanceof Collection<T> c) {
            return new ArrayList<>(c);
        }
        List<T> out = new ArrayList<>();
        for (T v : values) {
            out.add(v);
        }
        return out;
    }

    @Test
    void consumeProcessesRecordsUntilStopped() throws InterruptedException {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition("t", 0);
        consumer.assign(List.of(tp));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));

        // Add records incrementally during consumption
        consumer.addRecord(new ConsumerRecord<>("t", 0, 0L, "k", "v1"));

        KafkaSource<String> source = new KafkaSource<>(consumer, "t", new ObjectMapper(), String.class);

        AtomicInteger counter = new AtomicInteger(0);
        Thread thread = source.consumeAsync(value -> {
            counter.incrementAndGet();
            source.stop(); // Stop after first record
        });

        try {
            thread.join(5000L);
            assertEquals(1, counter.get());
            assertFalse(source.isRunning());
        } finally {
            source.close();
        }
    }

    @Test
    void consumeAsyncReturnsDaemonThread() throws InterruptedException {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition("t", 0);
        consumer.assign(List.of(tp));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));

        KafkaSource<String> source = new KafkaSource<>(consumer, "t", new ObjectMapper(), String.class);
        try {
            Thread thread = source.consumeAsync(v -> source.stop());
            assertTrue(thread.isDaemon());
            assertTrue(thread.getName().contains("kafka-consumer-"));
            thread.join(5000L);
        } finally {
            source.close();
        }
    }

    @Test
    void isRunningReflectsConsumerState() throws InterruptedException {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition("t", 0);
        consumer.assign(List.of(tp));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        // Add record so the consumer loop will process it
        consumer.addRecord(new ConsumerRecord<>("t", 0, 0L, "k", "v"));

        KafkaSource<String> source = new KafkaSource<>(consumer, "t", new ObjectMapper(), String.class);
        try {
            assertFalse(source.isRunning());

            Thread thread = source.consumeAsync(v -> source.stop());
            // Brief wait for thread to start and process record
            Thread.sleep(100);

            // After stop() is called, isRunning should return false
            thread.join(2000L);
            assertFalse(source.isRunning());
        } finally {
            source.close();
        }
    }

    @Test
    void getTopicReturnsTopicName() {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition("test-topic", 0);
        consumer.assign(List.of(tp));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));

        KafkaSource<String> source = new KafkaSource<>(consumer, "test-topic", new ObjectMapper(), String.class);
        try {
            assertEquals("test-topic", source.getTopic());
        } finally {
            source.close();
        }
    }

    @Test
    void pollSkipsNullValues() {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition("t", 0);
        consumer.assign(List.of(tp));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        consumer.addRecord(new ConsumerRecord<>("t", 0, 0L, "k", null));
        consumer.addRecord(new ConsumerRecord<>("t", 0, 1L, "k", "v"));

        KafkaSource<String> source = new KafkaSource<>(consumer, "t", new ObjectMapper(), String.class);
        try {
            List<String> out = toList(source.poll(Duration.ZERO));
            assertEquals(1, out.size());
            assertEquals("v", out.get(0));
        } finally {
            source.close();
        }
    }

    @Test
    void pollWithEmptyResult() {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition("t", 0);
        consumer.assign(List.of(tp));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));

        KafkaSource<String> source = new KafkaSource<>(consumer, "t", new ObjectMapper(), String.class);
        try {
            List<String> out = toList(source.poll(Duration.ZERO));
            assertTrue(out.isEmpty());
        } finally {
            source.close();
        }
    }
}
