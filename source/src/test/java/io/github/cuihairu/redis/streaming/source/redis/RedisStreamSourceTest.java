package io.github.cuihairu.redis.streaming.source.redis;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisStreamSourceTest {

    @SuppressWarnings("unchecked")
    private final RStream<String, String> stream = mock(RStream.class);

    private RedissonClient redissonWithStream() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String, String>getStream(eq("s"), any(StringCodec.class))).thenReturn(stream);
        return redisson;
    }

    private static StreamSource.SourceContext<String> collectingContext(List<String> sink) {
        return new StreamSource.SourceContext<>() {
            @Override
            public void collect(String element) {
                sink.add(element);
            }

            @Override
            public void collectWithTimestamp(String element, long timestamp) {
                sink.add(element);
            }

            @Override
            public Object getCheckpointLock() {
                return new Object();
            }

            @Override
            public boolean isStopped() {
                return false;
            }
        };
    }

    @Test
    void readsGroupsAcksAndDrainsWhenIdle() throws Exception {
        RedissonClient redisson = redissonWithStream();
        LinkedHashMap<StreamMessageId, Map<String, String>> batch = new LinkedHashMap<>();
        batch.put(new StreamMessageId(1000, 0), Map.of("value", "a"));
        batch.put(new StreamMessageId(1001, 0), Map.of("value", "b"));
        when(stream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenReturn(batch)
                .thenReturn(Map.of())
                .thenReturn(Map.of())
                .thenReturn(Map.of());

        RedisStreamSource<String> source = new RedisStreamSource<>(redisson, "s", "g", "c", String.class);
        List<String> out = new java.util.ArrayList<>();
        source.run(collectingContext(out));

        assertEquals(List.of("a", "b"), out);
        verify(stream).createGroup(any());
        verify(stream).ack("g", new StreamMessageId(1000, 0));
        verify(stream).ack("g", new StreamMessageId(1001, 0));
        verify(stream, times(4)).readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class));
    }

    @Test
    void missingValueFieldIsSkippedButAcked() throws Exception {
        RedissonClient redisson = redissonWithStream();
        LinkedHashMap<StreamMessageId, Map<String, String>> batch = new LinkedHashMap<>();
        batch.put(new StreamMessageId(1000, 0), Map.of("other", "x"));
        when(stream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenReturn(batch)
                .thenReturn(Map.of())
                .thenReturn(Map.of())
                .thenReturn(Map.of());

        RedisStreamSource<String> source = new RedisStreamSource<>(redisson, "s", "g", "c", String.class);
        List<String> out = new java.util.ArrayList<>();
        source.run(collectingContext(out));

        assertTrue(out.isEmpty());
        verify(stream).ack("g", new StreamMessageId(1000, 0));
    }

    @Test
    void jsonPayloadsDeserializeIntoTargetClass() throws Exception {
        record Pojo(int x, String y) {}

        RedissonClient redisson = redissonWithStream();
        LinkedHashMap<StreamMessageId, Map<String, String>> batch = new LinkedHashMap<>();
        batch.put(new StreamMessageId(1000, 0), Map.of("value", "{\"x\":7,\"y\":\"z\"}"));
        when(stream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenReturn(batch)
                .thenReturn(Map.of())
                .thenReturn(Map.of())
                .thenReturn(Map.of());

        RedisStreamSource<Pojo> source = new RedisStreamSource<>(redisson, "s", "g", "c", Pojo.class);
        List<Pojo> out = new java.util.ArrayList<>();
        source.run(new StreamSource.SourceContext<>() {
            @Override
            public void collect(Pojo element) {
                out.add(element);
            }

            @Override
            public void collectWithTimestamp(Pojo element, long timestamp) {
                out.add(element);
            }

            @Override
            public Object getCheckpointLock() {
                return new Object();
            }

            @Override
            public boolean isStopped() {
                return false;
            }
        });

        assertEquals(1, out.size());
        assertEquals(7, out.get(0).x());
        assertEquals("z", out.get(0).y());
    }

    @Test
    void validatesArguments() {
        RedissonClient redisson = mock(RedissonClient.class);
        assertThrows(NullPointerException.class, () -> new RedisStreamSource<String>(null, "s", "g", "c", String.class));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisStreamSource<>(redisson, "s", "g", "c", "value", String.class, 0, 10, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisStreamSource<>(redisson, "s", "g", "c", "value", String.class, 10, 10, 0));
    }
}
