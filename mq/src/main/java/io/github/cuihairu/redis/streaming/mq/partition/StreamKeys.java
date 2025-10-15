package io.github.cuihairu.redis.streaming.mq.partition;

/**
 * Helper for naming stream and control keys. Keep ASCII and simple naming.
 * Supports configurable prefixes via {@link #configure(String, String)}.
 */
public final class StreamKeys {

    private static volatile String CONTROL_PREFIX = "streaming:mq"; // meta/lease/retry
    private static volatile String STREAM_PREFIX = "stream:topic";  // data streams (partitions/DLQ)

    private StreamKeys() {}

    /** Configure key prefixes (optional; defaults are backward compatible). */
    public static void configure(String controlPrefix, String streamPrefix) {
        if (controlPrefix != null && !controlPrefix.isBlank()) CONTROL_PREFIX = controlPrefix;
        if (streamPrefix != null && !streamPrefix.isBlank()) STREAM_PREFIX = streamPrefix;
    }

    // Expose for internal callers that need to build patterns
    public static String streamPrefix() { return STREAM_PREFIX; }
    public static String controlPrefix() { return CONTROL_PREFIX; }

    public static String partitionStream(String topic, int partitionId) {
        return STREAM_PREFIX + ":" + topic + ":p:" + partitionId;
    }

    public static String dlq(String topic) {
        return STREAM_PREFIX + ":" + topic + ":dlq";
    }

    public static String topicMeta(String topic) {
        return CONTROL_PREFIX + ":topic:" + topic + ":meta";
    }

    public static String topicPartitionsSet(String topic) {
        return CONTROL_PREFIX + ":topic:" + topic + ":partitions";
    }

    public static String lease(String topic, String group, int partitionId) {
        return CONTROL_PREFIX + ":lease:" + topic + ":" + group + ":" + partitionId;
    }

    public static String retryBucket(String topic) {
        return CONTROL_PREFIX + ":retry:" + topic;
    }

    public static String retryItem(String topic, String id) {
        return CONTROL_PREFIX + ":retry:item:" + topic + ":" + id;
    }

    /** Global topics registry set key: {controlPrefix}:topics:registry */
    public static String topicsRegistry() {
        return CONTROL_PREFIX + ":topics:registry";
    }
}
