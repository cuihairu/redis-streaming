package io.github.cuihairu.redis.streaming.mq.partition;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;

import java.util.regex.Pattern;

/**
 * Helper for naming stream and control keys. Keep ASCII and simple naming.
 * Supports configurable prefixes via {@link #configure(String, String)}.
 *
 * <p>Instances carry a tenant namespace (see docs/Multi-Tenancy-Design.md): when the
 * tenant is not {@link #DEFAULT_TENANT}, a {@code :tenant} segment is inserted right
 * after the configured prefix, so two tenants never share a stream, DLQ, lease or
 * meta key. The static methods remain the single-tenant compatibility entry: they
 * delegate to a shared view built from the process-wide {@link #configure} values
 * (no tenant segment).</p>
 *
 * <p>Components that own an {@link MqOptions} should build their view with
 * {@link #of(MqOptions)} and use the instance methods, so producer, consumer and
 * admin resolve identical keys for identical options. With the default tenant the
 * derived keys are byte-identical to the pre-tenant layout (zero migration).</p>
 */
public final class StreamKeys {

    /** Tenant used when no isolation is requested; produces no key segment. */
    public static final String DEFAULT_TENANT = "default";

    private static final Pattern TENANT_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static volatile String CONTROL_PREFIX = "streaming:mq"; // meta/lease/retry
    private static volatile String STREAM_PREFIX = "stream:topic";  // data streams (partitions/DLQ)
    private static volatile String TENANT = DEFAULT_TENANT;

    private final String controlPrefix;
    private final String streamPrefix;
    private final String tenant;

    /** A view with the given prefixes and no tenant segment. */
    public StreamKeys(String controlPrefix, String streamPrefix) {
        this(controlPrefix, streamPrefix, DEFAULT_TENANT);
    }

    /** A view with the given prefixes and tenant namespace. */
    public StreamKeys(String controlPrefix, String streamPrefix, String tenant) {
        this.controlPrefix = normalizePrefix(controlPrefix, "streaming:mq");
        this.streamPrefix = normalizePrefix(streamPrefix, "stream:topic");
        this.tenant = normalizeTenant(tenant);
    }

    /** Build a view from MQ options (prefixes and tenant). */
    public static StreamKeys of(MqOptions options) {
        if (options == null) {
            return shared();
        }
        return new StreamKeys(options.getKeyPrefix(), options.getStreamKeyPrefix(), options.getTenant());
    }

    /** The process-wide default view (configured prefixes, no tenant segment). */
    public static StreamKeys shared() {
        return new StreamKeys(CONTROL_PREFIX, STREAM_PREFIX, TENANT);
    }

    /** Configure key prefixes (optional; defaults are backward compatible). */
    public static void configure(String controlPrefix, String streamPrefix) {
        if (controlPrefix != null && !controlPrefix.isBlank()) CONTROL_PREFIX = controlPrefix;
        if (streamPrefix != null && !streamPrefix.isBlank()) STREAM_PREFIX = streamPrefix;
    }

    /**
     * Configure key prefixes and the process-wide tenant used by the static entry.
     * A blank/null tenant or {@link #DEFAULT_TENANT} disables the segment.
     *
     * @throws IllegalArgumentException when the tenant is non-blank but not a valid name
     */
    public static void configure(String controlPrefix, String streamPrefix, String tenant) {
        configure(controlPrefix, streamPrefix);
        TENANT = normalizeTenant(tenant);
    }

    // Expose for internal callers that need to build patterns
    public static String streamPrefix() { return STREAM_PREFIX; }
    public static String controlPrefix() { return CONTROL_PREFIX; }

    private static String normalizePrefix(String prefix, String fallback) {
        return (prefix == null || prefix.isBlank()) ? fallback : prefix;
    }

    /**
     * Normalize a tenant name: null/blank/{@link #DEFAULT_TENANT} collapse to
     * {@link #DEFAULT_TENANT} (no key segment); anything else must be 1..64 chars of
     * {@code [A-Za-z0-9._-]} so the derived keys stay unambiguous (no colon).
     */
    public static String normalizeTenant(String tenant) {
        if (tenant == null || tenant.isBlank() || DEFAULT_TENANT.equals(tenant)) {
            return DEFAULT_TENANT;
        }
        if (!TENANT_PATTERN.matcher(tenant).matches()) {
            throw new IllegalArgumentException(
                    "tenant must match [A-Za-z0-9._-]{1,64} (no colon), got: " + tenant);
        }
        return tenant;
    }

    /** The normalized tenant of this view. */
    // ---- instance (tenant-scoped) key builders ----

    public String getTenant() { return tenant; }

    public String getControlPrefix() { return controlPrefix; }

    public String getStreamPrefix() { return streamPrefix; }

    /** @return "" for the default tenant, else ":" + tenant (insert after the prefix). */
    public String tenantSegment() {
        return DEFAULT_TENANT.equals(tenant) ? "" : ":" + tenant;
    }

    public String partitionStreamKey(String topic, int partitionId) {
        return streamPrefix + tenantSegment() + ":" + topic + ":p:" + partitionId;
    }

    public String dlqKey(String topic) {
        return streamPrefix + tenantSegment() + ":" + topic + ":dlq";
    }

    public String topicMetaKey(String topic) {
        return controlPrefix + tenantSegment() + ":topic:" + topic + ":meta";
    }

    public String topicPartitionsSetKey(String topic) {
        return controlPrefix + tenantSegment() + ":topic:" + topic + ":partitions";
    }

    public String leaseKey(String topic, String group, int partitionId) {
        return controlPrefix + tenantSegment() + ":lease:" + topic + ":" + group + ":" + partitionId;
    }

    public String retryBucketKey(String topic) {
        return controlPrefix + tenantSegment() + ":retry:" + topic;
    }

    public String retryItemKey(String topic, String id) {
        return controlPrefix + tenantSegment() + ":retry:item:" + topic + ":" + id;
    }

    /** Global topics registry set key: {controlPrefix}[:tenant]:topics:registry */
    public String topicsRegistryKey() {
        return controlPrefix + tenantSegment() + ":topics:registry";
    }

    /** Commit frontier hash for a partition: HSET field=group value=lastStreamId */
    public String commitFrontierKey(String topic, int partitionId) {
        return controlPrefix + tenantSegment() + ":commit:" + topic + ":p:" + partitionId;
    }

    /** Ack set for a message: acks collected per group to support all-groups-ack policy. */
    public String ackSetKey(String topic, int partitionId, String messageId) {
        return controlPrefix + tenantSegment() + ":acks:" + topic + ":p:" + partitionId + ":" + messageId;
    }

    /** A view with this view's prefixes and the given tenant (normalized). */
    public StreamKeys withTenant(String tenant) {
        return new StreamKeys(controlPrefix, streamPrefix, tenant);
    }

    // ---- static compatibility entry (single-tenant, process-wide configure) ----

    /** The process-wide view for static helpers (no tenant segment). */

    public static String partitionStream(String topic, int partitionId) {
        return shared().partitionStreamKey(topic, partitionId);
    }

    public static String dlq(String topic) {
        return shared().dlqKey(topic);
    }

    public static String topicMeta(String topic) {
        return shared().topicMetaKey(topic);
    }

    public static String topicPartitionsSet(String topic) {
        return shared().topicPartitionsSetKey(topic);
    }

    public static String lease(String topic, String group, int partitionId) {
        return shared().leaseKey(topic, group, partitionId);
    }

    public static String retryBucket(String topic) {
        return shared().retryBucketKey(topic);
    }

    public static String retryItem(String topic, String id) {
        return shared().retryItemKey(topic, id);
    }

    public static String topicsRegistry() {
        return shared().topicsRegistryKey();
    }

    public static String commitFrontier(String topic, int partitionId) {
        return shared().commitFrontierKey(topic, partitionId);
    }

    public static String ackSet(String topic, int partitionId, String messageId) {
        return shared().ackSetKey(topic, partitionId, messageId);
    }
}
