package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Two-level gate separating the runtime's exclusive control operations (checkpoint flows and
 * {@code scaleParallelism}) from the stop-the-world state they need. One level per concern:
 *
 * <ul>
 *   <li><b>serialization</b> — at most one control operation (periodic checkpoint, manual
 *       checkpoint trigger, scale) in flight at a time. Two concurrent checkpoints would
 *       double-drain and allocate colliding snapshot ids; a scale racing either mutates the
 *       same subtask lists and partition assignments mid-operation.</li>
 *   <li><b>full silence</b> — consumers paused and in-flight drained (or equivalent subtask
 *       quiescence). The aligned stop-the-world checkpoint holds it while it snapshots, and
 *       scale holds it while it repins/removes/adds subtasks — the aligned checkpoint's
 *       pause+drain would otherwise observe subtask lists mid-surgery.</li>
 * </ul>
 *
 * <p>Every holder that takes full silence also holds serialization (acquired in that order,
 * released in reverse), so {@code fullSilence ⇒ serialization} is the class invariant and no
 * acquisition order can deadlock: nobody waits on serialization while holding full silence.
 *
 * <p>The levels are separate because the planned unaligned checkpoint (checkpoint barrier
 * design A③) needs the opposite combination — serialized but <em>not</em> silent, consumers
 * keep flowing while offsets and state are snapshotted. Its entry takes serialization only;
 * this split is what makes that path addable without touching the existing holders.
 */
public final class CheckpointGate {

    private final AtomicBoolean exclusive = new AtomicBoolean(false);
    private final AtomicBoolean fullSilence = new AtomicBoolean(false);

    /**
     * Begin an aligned (stop-the-world) checkpoint: serialization first, then full silence.
     * Returns {@code false} (with no state left behind) when either level is held.
     */
    public boolean tryBeginAlignedCheckpoint() {
        return begin();
    }

    /** End an aligned checkpoint started by {@link #tryBeginAlignedCheckpoint()}. */
    public void endAlignedCheckpoint() {
        end();
    }

    /**
     * Begin a scale: scale still needs full silence (its subtask surgery must not overlap the
     * aligned checkpoint's pause+drain) and the same serialization as every control operation.
     * Returns {@code false} (with no state left behind) when either level is held.
     */
    public boolean tryBeginScale() {
        return begin();
    }

    /** End a scale started by {@link #tryBeginScale()}. */
    public void endScale() {
        end();
    }

    /** Whether any control operation (checkpoint flow or scale) currently holds the gate. */
    public boolean isExclusive() {
        return exclusive.get();
    }

    /** Whether the full-silence level is currently held (a superset view of {@link #isExclusive()}). */
    public boolean isFullSilence() {
        return fullSilence.get();
    }

    private boolean begin() {
        if (!exclusive.compareAndSet(false, true)) {
            return false;
        }
        if (!fullSilence.compareAndSet(false, true)) {
            // defensive: unreachable while the fullSilence⇒exclusive invariant holds, but a
            // stranded serialization level here would wedge every future control operation
            exclusive.set(false);
            return false;
        }
        return true;
    }

    private void end() {
        fullSilence.set(false);
        exclusive.set(false);
    }
}
