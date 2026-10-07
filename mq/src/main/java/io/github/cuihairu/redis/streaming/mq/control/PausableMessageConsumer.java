package io.github.cuihairu.redis.streaming.mq.control;

/**
 * Optional control interface for message consumers.
 *
 * <p>Used by higher-level runtimes to implement stop-the-world checkpointing.</p>
 */
public interface PausableMessageConsumer {

    void pause();

    void resume();

    boolean isPaused();

    /**
     * Messages currently inside a handler. Decrements back to 0 once every handler
     * returned, so callers can await a settled state on a running consumer.
     */
    long inFlight();

    /**
     * Checkpoint-barrier view of outstanding work: {@link #inFlight()} plus any read/claim
     * that has fetched messages whose handler accounting has not started yet (a blocked
     * poll that is about to hand a batch to the handlers). A checkpoint that waits for this
     * to reach 0 knows no fetched message can still be invoked into its two-phase epochs.
     * Defaults to {@link #inFlight()} for implementations without a read phase.
     */
    default long inFlightBarrier() {
        return inFlight();
    }
}

