package io.github.cuihairu.redis.streaming.runtime.redis.control;

/**
 * Pluggable authorization hook for control-plane operations.
 *
 * <p>The framework does not invent an authentication system; callers supply the actor
 * identity explicitly (ops tools / UI) or fall back to the {@code user.name} system
 * property. Implementations decide whether the actor may perform the operation and
 * throw {@link ControlPlaneAccessDeniedException} to reject it. Denied attempts are
 * audited regardless of the outcome.</p>
 */
@FunctionalInterface
public interface ControlPlaneAuthorizer {

    /**
     * Authorize a control-plane operation.
     *
     * @param actor   identity of the caller (never blank)
     * @param op      operation being attempted
     * @param jobName target job name
     * @throws ControlPlaneAccessDeniedException when the operation must be rejected
     */
    void authorize(String actor, JobControlOp op, String jobName) throws ControlPlaneAccessDeniedException;

    /**
     * Tenant-aware hook (docs/Multi-Tenancy-Design.md, control-plane step): lets
     * implementations scope decisions per tenant. Defaults to the tenant-blind
     * {@link #authorize(String, JobControlOp, String)} so existing authorizers keep working.
     *
     * @param tenant  tenant namespace of the target job (never null, normalized)
     * @param actor   identity of the caller (never blank)
     * @param op      operation being attempted
     * @param jobName target job name
     * @throws ControlPlaneAccessDeniedException when the operation must be rejected
     */
    default void authorize(String tenant, String actor, JobControlOp op, String jobName)
            throws ControlPlaneAccessDeniedException {
        authorize(actor, op, jobName);
    }

    /**
     * @return an authorizer that allows every operation (the default).
     */
    static ControlPlaneAuthorizer allowAll() {
        return (actor, op, jobName) -> {
        };
    }
}
