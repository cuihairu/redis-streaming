package io.github.cuihairu.redis.streaming.api.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Covers default methods and value type in the core stream API. */
class CoreApiDefaultsCoverageTest {

    @Test
    void checkpointAwareSinkDefaultsAreNoOps() throws Exception {
        CheckpointAwareSink<String> sink = new CheckpointAwareSink<>() {
            @Override
            public void invoke(String value) {
            }
        };
        assertDoesNotThrow(() -> sink.onCheckpointStart(1));
        assertDoesNotThrow(() -> sink.onCheckpointComplete(1));
        assertDoesNotThrow(() -> sink.onCheckpointAbort(1, new IllegalStateException("x")));
        assertDoesNotThrow(() -> sink.onCheckpointRestore(1));
    }

    @Test
    void streamSourceDefaultHooksAreNoOps() throws Exception {
        StreamSource<String> source = new StreamSource<>() {
            @Override
            public void run(SourceContext<String> ctx) {
            }
        };
        assertDoesNotThrow(source::open);
        assertDoesNotThrow(source::cancel);
        assertDoesNotThrow(source::close);
    }

    @Test
    void keyedProcessFunctionTimerDefaultsAreNoOps() throws Exception {
        KeyedProcessFunction<String, String, String> fn = new KeyedProcessFunction<>() {
            @Override
            public void processElement(String value, String key, Context ctx, Collector<String> out) {
            }
        };
        assertDoesNotThrow(() -> fn.onProcessingTime(1L, "k", null, null));
        assertDoesNotThrow(() -> fn.onEventTime(2L, "k", null, null));
    }

    @Test
    void idempotentRecordCompactConstructorValidates() {
        IdempotentRecord record = new IdempotentRecord("key-1", "payload");
        assertEquals("key-1", record.id());
        assertNotNull(record.value());
    }
}
