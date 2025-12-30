package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.watermark.generators.AscendingTimestampWatermarkGenerator;
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryDataStreamTimestampAssignerTest {

    @Test
    void windowCountUsesAssignedEventTimestamp() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        record Event(long ts, String value) {
        }

        List<Long> counts = new ArrayList<>();
        env.fromElements(
                        new Event(1000, "a"),
                        new Event(1500, "b"),
                        new Event(2500, "c"))
                .assignTimestampsAndWatermarks((event, recordTs) -> event.ts(),
                        new AscendingTimestampWatermarkGenerator<>())
                .keyBy(v -> "k")
                .window(TumblingWindow.of(Duration.ofMillis(1000)))
                .count()
                .addSink(counts::add);

        assertEquals(List.of(2L, 1L), counts);
    }
}

