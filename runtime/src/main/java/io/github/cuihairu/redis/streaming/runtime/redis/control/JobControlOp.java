package io.github.cuihairu.redis.streaming.runtime.redis.control;

/**
 * Control-plane operations passed to a {@link ControlPlaneAuthorizer}.
 */
public enum JobControlOp {
    /** Create and register a new job spec. */
    SUBMIT,
    /** Apply a mutation to an existing job spec (new version). */
    UPGRADE,
    /** Redeploy the previous spec version as a new version. */
    ROLLBACK,
    /** Mark the job as desired-stopped. */
    STOP,
    /** Re-enable a stopped job for deployment. */
    RESUME,
    /** Agent-side status report (only failures are audited). */
    REPORT_STATUS
}
