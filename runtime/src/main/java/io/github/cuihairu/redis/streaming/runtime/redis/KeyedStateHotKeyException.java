package io.github.cuihairu.redis.streaming.runtime.redis;

/**
 * Thrown by the keyed-state hot-key handling when
 * {@link RedisRuntimeConfig.HotKeyPolicy#FAIL_FAST} is configured and a write targets a
 * keyed-state hash whose field count exceeded
 * {@link RedisRuntimeConfig#getKeyedStateHotKeyFieldsWarnThreshold()} within the active
 * handling window.
 *
 * <p>The MQ consumer's existing retry/backoff machinery treats this like any processing
 * failure: the record is retried with backoff (backpressure against the hot key) and
 * eventually routed to the dead-letter queue after the configured attempts — operators can
 * then re-drive or discard the diverted records.</p>
 */
public class KeyedStateHotKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public KeyedStateHotKeyException(String message) {
        super(message);
    }

    public KeyedStateHotKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
