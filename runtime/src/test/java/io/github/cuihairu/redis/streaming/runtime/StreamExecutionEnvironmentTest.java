package io.github.cuihairu.redis.streaming.runtime;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamExecutionEnvironmentTest {

    @Test
    void fromElementsSupportsBasicOperators() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        List<String> out = new ArrayList<>();
        env.fromElements("a b", "c", "d e")
                .flatMap(line -> Arrays.asList(line.split(" ")))
                .filter(word -> !"c".equals(word))
                .map(String::toUpperCase)
                .addSink(out::add);

        assertEquals(List.of("A", "B", "D", "E"), out);
    }

    @Test
    void keyedProcessValueStateIsIsolatedByKey() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        StateDescriptor<Integer> descriptor = new StateDescriptor<>("count", Integer.class, 0);
        KeyedStream<String, String> keyed = env.fromElements("a", "b", "a").keyBy(v -> v);
        ValueState<Integer> count = keyed.getState(descriptor);

        List<String> out = new ArrayList<>();
        keyed.<String>process((key, value, ctx, collector) -> {
                    int next = count.value() + 1;
                    count.update(next);
                    collector.collect(key + ":" + next);
                })
                .addSink(out::add);

        assertEquals(List.of("a:1", "b:1", "a:2"), out);
    }

    @Test
    void addSourceCollectsElements() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        StreamSource<Integer> source = ctx -> {
            ctx.collect(1);
            ctx.collectWithTimestamp(2, 123L);
            ctx.collect(3);
        };

        List<Integer> out = new ArrayList<>();
        env.addSource(source).addSink(out::add);

        assertEquals(List.of(1, 2, 3), out);
    }
}
