package io.github.cuihairu.redis.streaming.mq.partition;

/**
 * Helper for naming stream and control keys. Keep ASCII and simple naming.
 */
public final class StreamKeys {

    private StreamKeys() {}

    public static String partitionStream(String topic, int partitionId) {
        return "stream:topic:" + topic + ":p:" + partitionId;
    }

    public static String dlq(String topic) {
        return "stream:topic:" + topic + ":dlq";
    }

    public static String topicMeta(String topic) {
        return "streaming:mq:topic:" + topic + ":meta";
    }

    public static String topicPartitionsSet(String topic) {
        return "streaming:mq:topic:" + topic + ":partitions";
    }

    public static String lease(String topic, String group, int partitionId) {
        return "streaming:mq:lease:" + topic + ":" + group + ":" + partitionId;
    }

    public static String retryBucket(String topic) {
        return "streaming:mq:retry:" + topic;
    }

    public static String retryItem(String topic, String id) {
        return "streaming:mq:retry:item:" + topic + ":" + id;
    }
}
