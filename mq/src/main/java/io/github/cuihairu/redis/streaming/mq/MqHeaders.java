package io.github.cuihairu.redis.streaming.mq;

/**
 * Common header keys used by the MQ module (public API).
 * Keep ASCII-only names and document semantics clearly.
 */
public final class MqHeaders {
    private MqHeaders() {}

    /** Force routing to a specific partition (int). Producer/router respects this when present. */
    public static final String FORCE_PARTITION_ID = "x-force-partition-id";

    /** Partition id hint placed into headers by consumers for convenience (string int). */
    public static final String PARTITION_ID = "partitionId";

    /** Internal marker set when a message's external payload reference is missing at consume time. */
    public static final String PAYLOAD_MISSING = "x-payload-missing";

    /** Internal header carrying the missing payload reference key when available. */
    public static final String PAYLOAD_MISSING_REF = "x-payload-missing-ref";

    /**
     * Stable id for retries/replays: the first Stream entry id seen for this logical message.
     *
     * <p>On retries, MQ may re-enqueue the message with a new Redis Stream entry id; this header preserves the
     * original id for correlation and deduplication.</p>
     */
    public static final String ORIGINAL_MESSAGE_ID = "x-original-message-id";

    /**
     * When set to "true", the consumer will not ACK on {@link io.github.cuihairu.redis.streaming.mq.MessageHandleResult#SUCCESS}.
     *
     * <p>Intended for runtimes that coordinate ACK with checkpoints; leaving a message pending is safe as long as
     * checkpoints happen faster than the pending-claim timeout.</p>
     */
    public static final String DEFER_ACK = "x-defer-ack";
}
