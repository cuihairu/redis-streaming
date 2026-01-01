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

    long inFlight();
}

