package io.github.cuihairu.redis.streaming.source.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for the paths {@link KafkaSourceTest} deliberately avoids: the constructors that build
 * a <em>real</em> {@code KafkaConsumer}, the per-record failure arm of the consume loop, and the
 * second-chance poll inside {@code ensureAssigned()}.
 *
 * <p>No broker is required. A {@code KafkaConsumer} is lazy — it resolves bootstrap servers on
 * the first poll, not in the constructor — so the public bootstrap-server constructors can be
 * instantiated (and closed) against an unreachable address, which is exactly the situation a
 * misconfigured deployment starts in.</p>
 */
class KafkaSourceLifecycleCoverageTest {

    /** Unreachable on purpose: proves the constructor does not dial the broker. */
    private static final String UNREACHABLE = "127.0.0.1:1";

    private static Properties consumerProps() {
        return KafkaSource.buildDefaultConsumerProperties(UNREACHABLE, "g-coverage");
    }

    @SuppressWarnings("unchecked")
    private static Consumer<String, String> emptyMockConsumer() {
        return (Consumer<String, String>) mock(Consumer.class);
    }

    /* ---------- real-consumer constructors (lazy connect, no broker needed) ---------- */

    @Test
    void bootstrapServerConstructorAppliesDefaultsWithoutContactingTheBroker() {
        KafkaSource<String> source = new KafkaSource<>(UNREACHABLE, "g-coverage", "topic-a", String.class);
        try {
            assertEquals("topic-a", source.getTopic());
            assertFalse(source.isRunning());
        } finally {
            assertDoesNotThrow(source::close);
        }
    }

    @Test
    void objectMapperConstructorReusesTheSameDefaults() {
        KafkaSource<String> source = new KafkaSource<>(UNREACHABLE, "g-coverage", "topic-b",
                new ObjectMapper(), String.class);
        try {
            assertEquals("topic-b", source.getTopic());
        } finally {
            assertDoesNotThrow(source::close);
        }
    }

    @Test
    void propertiesConstructorBuildsAWorkingSourceWithCustomProperties() {
        Properties props = consumerProps();
        props.setProperty("max.poll.records", "7");

        // NOTE: no poll here. The real consumer is subscribed but never polled: a poll against
        // the unreachable address retries with backoff for minutes, which is a broker-outage
        // behaviour, not something a unit test should wait out. Construction + close is what
        // this test pins.
        KafkaSource<String> source = new KafkaSource<>(props, "topic-c", new ObjectMapper(), String.class);
        try {
            assertEquals("topic-c", source.getTopic());
            assertFalse(source.isRunning());
        } finally {
            assertDoesNotThrow(source::close);
        }
    }

    @Test
    void constructorsRejectMissingArguments() {
        assertThrows(NullPointerException.class,
                () -> KafkaSource.buildDefaultConsumerProperties(null, "g"));
        assertThrows(NullPointerException.class,
                () -> KafkaSource.buildDefaultConsumerProperties(UNREACHABLE, null));
        assertThrows(NullPointerException.class,
                () -> new KafkaSource<>((Properties) null, "t", new ObjectMapper(), String.class));
        assertThrows(NullPointerException.class,
                () -> new KafkaSource<>(consumerProps(), null, new ObjectMapper(), String.class));
        assertThrows(NullPointerException.class,
                () -> new KafkaSource<>(consumerProps(), "t", null, String.class));
        assertThrows(NullPointerException.class,
                () -> new KafkaSource<>(consumerProps(), "t", new ObjectMapper(), null));
        assertThrows(NullPointerException.class,
                () -> new KafkaSource<>((Consumer<String, String>) null, "t", new ObjectMapper(), String.class));
        assertThrows(NullPointerException.class,
                () -> new KafkaSource<>(emptyMockConsumer(), null, new ObjectMapper(), String.class));
        assertThrows(NullPointerException.class,
                () -> new KafkaSource<>(emptyMockConsumer(), "t", null, String.class));
        assertThrows(NullPointerException.class,
                () -> new KafkaSource<>(emptyMockConsumer(), "t", new ObjectMapper(), null));
    }

    @Test
    void autoSubscribeConstructorSubscribesWhileThePackagePrivateOneDoesNot() {
        Consumer<String, String> auto = emptyMockConsumer();
        when(auto.assignment()).thenReturn(Collections.emptySet());
        when(auto.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
        KafkaSource<String> subscribed = new KafkaSource<>(auto, "t", new ObjectMapper(), String.class, true);
        assertEquals("t", subscribed.getTopic());
        verify(auto).subscribe(List.of("t"));

        Consumer<String, String> manual = emptyMockConsumer();
        when(manual.assignment()).thenReturn(Collections.emptySet());
        when(manual.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
        new KafkaSource<String>(manual, "t", new ObjectMapper(), String.class);
        verify(manual, never()).subscribe(anyCollection());
    }

    /* ---------- consume loop: a failing handler must not kill the loop ---------- */

    @Test
    void consumeSkipsRecordsWhoseHandlerThrowsAndKeepsProcessing() throws InterruptedException {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition("t", 0);
        consumer.assign(List.of(tp));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        // tombstone first: a null value is skipped by deserialize() and must never reach the handler
        consumer.addRecord(new ConsumerRecord<>("t", 0, 0L, "k", null));
        consumer.addRecord(new ConsumerRecord<>("t", 0, 1L, "k", "boom"));
        consumer.addRecord(new ConsumerRecord<>("t", 0, 2L, "k", "last"));

        KafkaSource<String> source = new KafkaSource<>(consumer, "t", new ObjectMapper(), String.class);
        List<String> seen = new ArrayList<>();
        try {
            Thread thread = source.consumeAsync(value -> {
                seen.add(value);
                if ("boom".equals(value)) {
                    throw new IllegalStateException("handler failed");
                }
                source.stop();
            });
            thread.join(5000L);

            assertEquals(List.of("boom", "last"), seen,
                    "tombstones must be dropped and a throwing handler must not abort the loop");
            assertFalse(source.isRunning());
        } finally {
            source.close();
        }
    }

    @Test
    void consumeRequiresANonNullHandler() {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        KafkaSource<String> source = new KafkaSource<>(consumer, "t", new ObjectMapper(), String.class);
        try {
            assertThrows(NullPointerException.class, () -> source.consume(null));
        } finally {
            source.close();
        }
    }

    /* ---------- ensureAssigned: the second-chance poll and its failure arm ---------- */

    @Test
    void ensureAssignedRetriesWithAShortPollWhenTheFirstOneAssignsNothing() {
        Consumer<String, String> consumer = emptyMockConsumer();
        when(consumer.assignment()).thenReturn(Collections.emptySet());
        when(consumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());

        KafkaSource<String> source = new KafkaSource<>(consumer, "t", new ObjectMapper(), String.class, false);
        try {
            source.seekToBeginning();

            // best-effort escalation: poll(ZERO) first, then one short poll, then seek anyway
            verify(consumer).poll(Duration.ZERO);
            verify(consumer).poll(Duration.ofMillis(100));
            verify(consumer).seekToBeginning(Collections.emptySet());
        } finally {
            source.close();
        }
    }

    @Test
    void ensureAssignedSwallowsPollFailuresAndSeeksAnyway() {
        Consumer<String, String> consumer = emptyMockConsumer();
        when(consumer.assignment()).thenReturn(Collections.emptySet());
        when(consumer.poll(any(Duration.class))).thenThrow(new IllegalStateException("no coordinator"));

        KafkaSource<String> source = new KafkaSource<>(consumer, "t", new ObjectMapper(), String.class, false);
        try {
            assertDoesNotThrow(source::seekToEnd);
            verify(consumer).seekToEnd(Collections.emptySet());
        } finally {
            source.close();
        }
    }

    @Test
    void ensureAssignedSkipsPollingWhenPartitionsAreAlreadyAssigned() {
        Consumer<String, String> consumer = emptyMockConsumer();
        TopicPartition tp = new TopicPartition("t", 0);
        when(consumer.assignment()).thenReturn(Set.of(tp));
        when(consumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());

        KafkaSource<String> source = new KafkaSource<>(consumer, "t", new ObjectMapper(), String.class, false);
        try {
            source.seekToBeginning();
            verify(consumer, never()).poll(any(Duration.class));
            verify(consumer).seekToBeginning(Set.of(tp));
            assertNotNull(source.getTopic());
        } finally {
            source.close();
        }
    }
}
