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
}
