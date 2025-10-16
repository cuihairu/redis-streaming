package io.github.cuihairu.redis.streaming.reliability.dlq;

/**
 * Key helper for Dead Letter Queue streams. Defaults mirror MQ StreamKeys defaults
 * to keep compatibility. Only DLQ naming is provided here to avoid depending on MQ.
 */
public final class DlqKeys {
    private static volatile String STREAM_PREFIX = "stream:topic";

    private DlqKeys() {}

    /** Configure stream key prefix (optional). */
    public static void configure(String streamPrefix) {
        if (streamPrefix != null && !streamPrefix.isBlank()) STREAM_PREFIX = streamPrefix;
    }

    public static String dlq(String topic) {
        return STREAM_PREFIX + ":" + topic + ":dlq";
    }
}

