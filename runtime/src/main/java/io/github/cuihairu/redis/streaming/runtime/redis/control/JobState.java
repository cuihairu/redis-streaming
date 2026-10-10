package io.github.cuihairu.redis.streaming.runtime.redis.control;

/**
 * Lifecycle state of a control-plane managed job.
 *
 * <p>States are the <em>desired/observed</em> status recorded in Redis; the actual
 * in-process execution still belongs to the owning {@code RedisJobClient}.</p>
 */
public enum JobState {

    /**
     * Desired state is stopped: an agent holding the job locally should cancel it.
     */
    DESIRED_STOPPED,

    /**
     * Job spec is new (or re-enabled) and waiting to be claimed and deployed by an agent.
     */
    PENDING_DEPLOY,

    /**
     * An agent reports the job running with the expected spec version.
     */
    RUNNING,

    /**
     * The last deploy/upgrade attempt failed on the agent side; details are in the status entry.
     */
    FAILED
}
