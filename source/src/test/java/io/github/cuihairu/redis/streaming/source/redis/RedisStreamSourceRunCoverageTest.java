package io.github.cuihairu.redis.streaming.source.redis;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers RedisStreamSource bounded drain (run) and simple accessors. */
class RedisStreamSourceRunCoverageTest {

    @Test
    void runDrainsEntriesUntilIdleAndSkipsMissingField() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RStream<String, String> stream = mock(RStream.class);
        when(redisson.<String, String>getStream(eq("s1"), any(StringCodec.class))).thenReturn(stream);

        Map<StreamMessageId, Map<String, String>> first = new LinkedHashMap<>();
        first.put(new StreamMessageId(1, 0), Map.of("value", "hello"));
        first.put(new StreamMessageId(2, 0), Map.of("other", "no-value"));
        when(stream.readGroup(any(String.class), any(String.class), any(StreamReadGroupArgs.class)))
                .thenReturn(first)
                .thenReturn(Map.of());

        RedisStreamSource<String> source = new RedisStreamSource<>(redisson, "s1", "g1", "c1", String.class);
        List<String> out = new ArrayList<>();
        AtomicBoolean stopped = new AtomicBoolean(false);
        source.run(new StreamSource.SourceContext<>() {
            @Override
            public void collect(String element) {
                out.add(element);
            }
            @Override
            public void collectWithTimestamp(String element, long timestamp) {
                out.add(element);
            }
            @Override
            public Object getCheckpointLock() {
                return this;
            }
            @Override
            public boolean isStopped() {
                return stopped.get();
            }
        });

        assertEquals(List.of("hello"), out, "entry without value field is skipped");
        assertEquals("s1", source.getStreamName());
        assertEquals("g1", source.getConsumerGroup());
    }

    @Test
    void runToleratesConsumerGroupCreationFailure() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RStream<String, String> stream = mock(RStream.class);
        when(redisson.<String, String>getStream(eq("s2"), any(StringCodec.class))).thenReturn(stream);
        doThrow(new IllegalStateException("BUSYGROUP")).when(stream).createGroup(any());
        when(stream.readGroup(any(String.class), any(String.class), any(StreamReadGroupArgs.class)))
                .thenReturn(Map.of());

        RedisStreamSource<String> source = new RedisStreamSource<>(redisson, "s2", "g2", "c2", String.class);
        AtomicBoolean stopped = new AtomicBoolean(false);
        source.run(new StreamSource.SourceContext<>() {
            @Override
            public void collect(String element) {
            }
            @Override
            public void collectWithTimestamp(String element, long timestamp) {
            }
            @Override
            public Object getCheckpointLock() {
                return this;
            }
            @Override
            public boolean isStopped() {
                return stopped.get();
            }
        });
        assertTrue(true);
    }
}
