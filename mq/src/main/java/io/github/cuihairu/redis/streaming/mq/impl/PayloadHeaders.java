package io.github.cuihairu.redis.streaming.mq.impl;

/**
 * Constants for payload-related headers
 */
public final class PayloadHeaders {

    /**
     * Header key indicating that the actual payload is stored in a Redis hash
     * Value contains the hash key where the payload can be retrieved
     */
    public static final String PAYLOAD_HASH_REF = "x-payload-hash-ref";

    /**
     * Header key indicating the original size of the payload before storage
     */
    public static final String PAYLOAD_ORIGINAL_SIZE = "x-payload-original-size";

    /**
     * Header key indicating the type of payload storage (inline or hash)
     */
    public static final String PAYLOAD_STORAGE_TYPE = "x-payload-storage-type";

    /**
     * Storage type values
     */
    public static final String STORAGE_TYPE_INLINE = "inline";
    public static final String STORAGE_TYPE_HASH = "hash";

    /**
     * Maximum payload size before storing in hash (64KB)
     */
    public static final int MAX_INLINE_PAYLOAD_SIZE = 64 * 1024;

    private PayloadHeaders() {
        // utility class
    }
}