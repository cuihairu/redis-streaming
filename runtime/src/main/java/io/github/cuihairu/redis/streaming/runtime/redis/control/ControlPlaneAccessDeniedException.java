package io.github.cuihairu.redis.streaming.runtime.redis.control;

/**
 * Thrown when a {@link ControlPlaneAuthorizer} rejects a control-plane operation.
 * The denied attempt is still written to the audit stream before this exception is raised.
 */
public class ControlPlaneAccessDeniedException extends RuntimeException {

    public ControlPlaneAccessDeniedException(String message) {
        super(message);
    }
}
