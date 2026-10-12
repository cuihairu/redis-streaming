package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator.WatermarkOutput;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Barrier design P2: generalized {@code (name, snapshot, restore)} snapshot sources in the
 * in-memory checkpoint coordinator — named sources join the checkpoint cut under the
 * checkpoint lock, and watermark states register automatically from
 * {@code assignTimestampsAndWatermarks} pipeline segments.
 */
class InMemorySnapshotSourceTest {

    @Test
    void namedSourceSnapshotIsPartOfTheCheckpointCut() {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        AtomicReference<Object> payload = new AtomicReference<>("payload-1");
        coordinator.registerSnapshotSource("src", payload::get, v -> { });

        long id = coordinator.triggerCheckpoint();

        assertEquals("payload-1", coordinator.getCheckpoint(id).getStateSnapshot().getState("src"));
    }

    @Test
    void namedSourceRestoreReceivesItsSnapshot() {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        AtomicReference<Object> payload = new AtomicReference<>("payload-1");
        AtomicReference<Object> restoredInto = new AtomicReference<>();
        coordinator.registerSnapshotSource("src", payload::get, restoredInto::set);

        long id = coordinator.triggerCheckpoint();
        coordinator.restoreFromCheckpoint(id);

        assertEquals("payload-1", restoredInto.get());
    }

    @Test
    void snapshotAndRestoreRunUnderTheCheckpointLock() {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        AtomicBoolean snapshotUnderLock = new AtomicBoolean(false);
        AtomicBoolean restoreUnderLock = new AtomicBoolean(false);
        coordinator.registerSnapshotSource("src",
                () -> {
                    snapshotUnderLock.set(Thread.holdsLock(coordinator.checkpointLock()));
                    return "s";
                },
                v -> restoreUnderLock.set(Thread.holdsLock(coordinator.checkpointLock())));

        long id = coordinator.triggerCheckpoint();
        coordinator.restoreFromCheckpoint(id);

        assertTrue(snapshotUnderLock.get(), "snapshot must run under the checkpoint lock");
        assertTrue(restoreUnderLock.get(), "restore must run under the checkpoint lock");
    }

    @Test
    void duplicateAndBlankNamesAreRejected() {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        coordinator.registerSnapshotSource("src", () -> "s", v -> { });

        assertThrows(IllegalStateException.class,
                () -> coordinator.registerSnapshotSource("src", () -> "s", v -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.registerSnapshotSource(" ", () -> "s", v -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.registerSnapshotSource(null, () -> "s", v -> { }));
        assertThrows(NullPointerException.class,
                () -> coordinator.registerSnapshotSource("s2", null, v -> { }));
    }

    @Test
    void generatedWatermarkNamesSkipOccupiedSlots() {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();

        String n1 = coordinator.registerWatermarkState(new WatermarkState());
        String n2 = coordinator.registerWatermarkState(new WatermarkState());
        coordinator.registerSnapshotSource("watermark-3", () -> "x", v -> { });
        String n3 = coordinator.registerWatermarkState(new WatermarkState());

        assertEquals("watermark-1", n1);
        assertEquals("watermark-2", n2);
        assertEquals("watermark-4", n3);
        assertEquals(List.of("watermark-1", "watermark-2", "watermark-3", "watermark-4"),
                List.copyOf(coordinator.getRegisteredSourceNames()));
    }

    @Test
    void watermarkStateRoundTripsThroughTheCheckpoint() {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        List<Integer> out = new ArrayList<>();
        InMemoryDataStream.fromRecords(
                        () -> List.of(new InMemoryRecord<>(1, 10L), new InMemoryRecord<>(2, 20L)).iterator(),
                        coordinator)
                .assignTimestampsAndWatermarks(new WatermarkGenerator<Integer>() {
                    @Override
                    public void onEvent(Integer event, long eventTimestamp, WatermarkOutput output) {
                        output.emitWatermark(new Watermark(eventTimestamp));
                    }

                    @Override
                    public void onPeriodicEmit(WatermarkOutput output) {
                    }
                })
                .addSink(out::add);

        assertTrue(coordinator.getRegisteredSourceNames().stream().anyMatch(n -> n.startsWith("watermark-")),
                "the segment watermark state should be a registered snapshot source");

        long id = coordinator.triggerCheckpoint();
        Object state = coordinator.getCheckpoint(id).getStateSnapshot().getState("watermark-1");
        assertInstanceOf(WatermarkState.Snapshot.class, state);
        assertEquals(20L, ((WatermarkState.Snapshot) state).watermark());

        // a source with live state round-trips: the cut freezes it, later drift is rewound
        WatermarkState fresh = new WatermarkState();
        fresh.emit(new Watermark(99L));
        coordinator.registerSnapshotSource("wm-restore", fresh::snapshot, fresh::restoreSnapshot);
        long id2 = coordinator.triggerCheckpoint();
        fresh.emit(new Watermark(200L));
        assertEquals(200L, fresh.getWatermark());
        coordinator.restoreFromCheckpoint(id2);
        assertEquals(99L, fresh.getWatermark());
    }

    @Test
    void environmentBuiltWatermarkSegmentsRegisterWithTheCoordinator() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment().enableCheckpointing();
        List<Integer> out = new ArrayList<>();
        env.fromCollection(List.of(1, 2, 3))
                .assignTimestampsAndWatermarks(new WatermarkGenerator<Integer>() {
                    @Override
                    public void onEvent(Integer event, long eventTimestamp, WatermarkOutput output) {
                        output.emitWatermark(new Watermark(eventTimestamp));
                    }

                    @Override
                    public void onPeriodicEmit(WatermarkOutput output) {
                    }
                })
                .addSink(out::add);

        InMemoryCheckpointCoordinator coordinator = (InMemoryCheckpointCoordinator) env.getCheckpointCoordinator();
        assertTrue(coordinator.getRegisteredSourceNames().stream().anyMatch(n -> n.startsWith("watermark-")));

        long id = coordinator.triggerCheckpoint();
        assertNotNull(coordinator.getCheckpoint(id).getStateSnapshot().getState("watermark-1"));
        assertEquals(List.of(1, 2, 3), out);
    }
}
