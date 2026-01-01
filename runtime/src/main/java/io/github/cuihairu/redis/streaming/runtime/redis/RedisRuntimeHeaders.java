package io.github.cuihairu.redis.streaming.runtime.redis;

/**
 * Common header keys written by the Redis runtime into {@link io.github.cuihairu.redis.streaming.mq.Message#getHeaders()}.
 */
public final class RedisRuntimeHeaders {
    private RedisRuntimeHeaders() {
    }

    /** Job name that handled the message. */
    public static final String JOB_NAME = "x-runtime-job";

    /** Consumer group used by the job. */
    public static final String CONSUMER_GROUP = "x-runtime-group";

    /** Fully qualified exception type (best-effort) for the last processing error. */
    public static final String ERROR_TYPE = "x-runtime-error-type";

    /** Truncated exception message (best-effort) for the last processing error. */
    public static final String ERROR_MESSAGE = "x-runtime-error-message";
}

