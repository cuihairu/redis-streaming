package io.github.cuihairu.redis.streaming.api.stream;

import io.github.cuihairu.redis.streaming.api.watermark.TimestampAssigner;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import org.junit.jupiter.api.Test;

import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DataStreamDefaultMethodsTest {

    @Test
    void assignTimestampsAndWatermarksDefaultMethodsThrowWhenNotOverridden() {
        DataStream<String> ds = new DataStream<>() {
            @Override
            public <R> DataStream<R> map(Function<String, R> mapper) {
                throw new UnsupportedOperationException("not needed");
            }

            @Override
            public DataStream<String> filter(Predicate<String> predicate) {
                throw new UnsupportedOperationException("not needed");
            }

            @Override
            public <R> DataStream<R> flatMap(Function<String, Iterable<R>> mapper) {
                throw new UnsupportedOperationException("not needed");
            }

            @Override
            public <K> KeyedStream<K, String> keyBy(Function<String, K> keySelector) {
                throw new UnsupportedOperationException("not needed");
            }

            @Override
            public DataStream<String> addSink(StreamSink<String> sink) {
                throw new UnsupportedOperationException("not needed");
            }

            @Override
            public DataStream<String> print() {
                return this;
            }

            @Override
            public DataStream<String> print(String prefix) {
                return this;
            }
        };

        WatermarkGenerator<String> wm = new WatermarkGenerator<>() {
            @Override
            public void onEvent(String event, long eventTimestamp, WatermarkOutput output) {
            }

            @Override
            public void onPeriodicEmit(WatermarkOutput output) {
            }
        };
        TimestampAssigner<String> ts = (event, previous) -> previous;

        assertThrows(UnsupportedOperationException.class, () -> ds.assignTimestampsAndWatermarks(wm));
        assertThrows(UnsupportedOperationException.class, () -> ds.assignTimestampsAndWatermarks(ts, wm));
    }
}
