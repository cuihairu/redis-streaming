package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two-level control-gate lattice the runtime relies on: checkpoint flows and
 * {@code scaleParallelism} serialize against each other on the exclusive level, both hold
 * the full-silence level while active, and a refused begin never strands either level. This
 * is the lattice the planned unaligned checkpoint must slot into (serialization without
 * full silence) — a regression here changes the exclusivity contract, not just performance.
 */
class CheckpointGateTest {

    @Test
    void alignedCheckpointSerializesAgainstItselfAndScale() {
        CheckpointGate gate = new CheckpointGate();

        assertTrue(gate.tryBeginAlignedCheckpoint());
        assertTrue(gate.isExclusive(), "held checkpoint must report the exclusive level");
        assertTrue(gate.isFullSilence(), "aligned checkpoint holds full silence");
        assertFalse(gate.tryBeginAlignedCheckpoint(), "a second checkpoint flow must be refused");
        assertFalse(gate.tryBeginScale(), "scale must wait out an in-flight checkpoint");

        gate.endAlignedCheckpoint();
        assertFalse(gate.isExclusive());
        assertFalse(gate.isFullSilence());
        assertTrue(gate.tryBeginScale(), "released gate must serve the next holder");
        gate.endScale();
        assertTrue(gate.tryBeginAlignedCheckpoint());
        gate.endAlignedCheckpoint();
    }

    @Test
    void scaleSerializesAgainstItselfAndCheckpoint() {
        CheckpointGate gate = new CheckpointGate();

        assertTrue(gate.tryBeginScale());
        assertTrue(gate.isExclusive(), "held scale must report the exclusive level");
        assertTrue(gate.isFullSilence(), "scale still needs full silence");
        assertFalse(gate.tryBeginScale(), "a concurrent scale must be refused");
        assertFalse(gate.tryBeginAlignedCheckpoint(), "checkpoint must wait out a scale");

        gate.endScale();
        assertFalse(gate.isExclusive());
        assertFalse(gate.isFullSilence());
    }

    /**
     * The defensive branch: if the {@code fullSilence ⇒ exclusive} invariant is ever broken
     * (a future holder type keeping silence past its exclusive slot), a refused begin must
     * still release the serialization level it won — a stranded level would wedge every
     * future checkpoint and scale.
     */
    @Test
    void failedFullSilenceAcquisitionLeavesNoSerializationResidue() throws Exception {
        CheckpointGate gate = new CheckpointGate();
        AtomicBoolean silencePin = pinLevel(gate, "fullSilence", true);

        assertFalse(gate.tryBeginScale(), "pinned full silence must refuse the begin");
        assertFalse(levelValue(gate, "exclusive").get(), "refused begin must not strand serialization");
        assertFalse(gate.tryBeginAlignedCheckpoint(), "the aligned pair must behave the same");

        silencePin.set(false);
        assertTrue(gate.tryBeginScale(), "clearing the pin restores full service");
        gate.endScale();
        assertTrue(gate.tryBeginAlignedCheckpoint());
        gate.endAlignedCheckpoint();
    }

    @Test
    void contendedHoldersNeverOverlap() throws Exception {
        CheckpointGate gate = new CheckpointGate();
        AtomicBoolean inCritical = new AtomicBoolean(false);
        AtomicBoolean overlapped = new AtomicBoolean(false);
        AtomicInteger completed = new AtomicInteger();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            final boolean scale = t % 2 == 0;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    while (!(scale ? gate.tryBeginScale() : gate.tryBeginAlignedCheckpoint())) {
                        Thread.yield();
                    }
                    if (!inCritical.compareAndSet(false, true)) {
                        overlapped.set(true);
                    }
                    inCritical.set(false);
                    if (scale) {
                        gate.endScale();
                    } else {
                        gate.endAlignedCheckpoint();
                    }
                    completed.incrementAndGet();
                }
            });
        }
        for (Thread th : threads) {
            th.start();
        }
        for (Thread th : threads) {
            th.join(10_000);
            assertFalse(th.isAlive(), "contender must finish its loop");
        }
        assertFalse(overlapped.get(), "two holders must never overlap");
        assertEquals(threads.length * 500, completed.get());
        assertFalse(gate.isExclusive(), "gate must be fully released after the storm");
        assertFalse(gate.isFullSilence());
    }

    private static AtomicBoolean pinLevel(CheckpointGate gate, String name, boolean value) throws Exception {
        AtomicBoolean level = levelValue(gate, name);
        level.set(value);
        return level;
    }

    private static AtomicBoolean levelValue(CheckpointGate gate, String name) throws Exception {
        Field f = CheckpointGate.class.getDeclaredField(name);
        f.setAccessible(true);
        return (AtomicBoolean) f.get(gate);
    }
}
