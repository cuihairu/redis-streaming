package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the two residual behaviors of the {@code runtime.internal} package: a keyed-process
 * timer callback that throws must surface wrapped in a RuntimeException (message "Keyed
 * process timer callback failed", original as cause, timestamp restored in the finally
 * block), and {@link InMemoryCheckpointCoordinator#restoreFromCheckpoint(long)} must skip
 * a registered store whose id is absent from the checkpoint snapshot (the store keeps its
 * own state instead of being cleared).
 *
 * <p>Note on the package's 99% branch figure: the only missed branch left is the
 * enum-switch synthetic default arm of {@code TimerQueue.fire} — unreachable from tests
 * because timers are only ever registered as PROCESSING_TIME or EVENT_TIME; closing it
 * would require a production-code change, which is out of scope.</p>
 */
class InMemoryInternalResidualCoverageTest {

    @Test
    void failingTimerCallbackSurfacesAsRuntimeException() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1).iterator(),
                v -> "k"
        );

        KeyedProcessFunction<String, Integer, String> fn = new KeyedProcessFunction<>() {
            @Override
            public void processElement(String key, Integer value, io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction.Context ctx,
                                       Collector<String> out) {
                ctx.registerProcessingTimeTimer(ctx.currentProcessingTime() + 1);
            }

            @Override
            public void onProcessingTime(long timestamp, String key, Context ctx, Collector<String> out) {
                throw new IllegalStateException("boom from timer");
            }
        };

        List<String> out = new ArrayList<>();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> keyed.process(fn).addSink(out::add));
        assertEquals("Keyed process timer callback failed", ex.getMessage());
        assertNotNull(ex.getCause());
        assertEquals("boom from timer", ex.getCause().getMessage());
        // the failure aborts the pipeline: no element ever reached the sink
        assertEquals(List.of(), out);
    }

    @Test
    void restoreSkipsStoresAbsentFromTheCheckpointSnapshot() {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();

        InMemoryKeyedStateStore<String> first = new InMemoryKeyedStateStore<>();
        String firstId = coordinator.registerStore(first);
        first.put("counts", "k1", 10);
        long checkpointId = coordinator.triggerCheckpoint();

        // diverge the snapshotted store and register a NEW store after the checkpoint —
        // the snapshot predates it, so it has no entry to restore from
        first.put("counts", "k1", 999);
        InMemoryKeyedStateStore<String> late = new InMemoryKeyedStateStore<>();
        coordinator.registerStore(late);
        late.put("counts", "k2", 7);

        coordinator.restoreFromCheckpoint(checkpointId);

        assertEquals(10, first.get("counts", "k1"),
                "the snapshot entry must overwrite the post-checkpoint divergence");
        assertEquals(7, late.get("counts", "k2"),
                "a store absent from the snapshot must be skipped, not cleared");
    }
}
