package io.github.cuihairu.redis.streaming.mq.partition;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tenant namespacing of {@link StreamKeys}: the default tenant must produce
 * byte-identical keys to the pre-tenant layout (zero migration), non-default
 * tenants get a {@code :{tenant}} segment right after the prefix, and tenant
 * names are validated so derived keys stay unambiguous.
 */
class StreamKeysTenantTest {

    @AfterEach
    void restoreDefaults() {
        StreamKeys.configure("streaming:mq", "stream:topic", StreamKeys.DEFAULT_TENANT);
    }

    // ===== default tenant = legacy layout =====

    @Test
    void defaultTenantKeepsLegacyKeys() {
        StreamKeys keys = StreamKeys.shared();

        assertEquals("", keys.tenantSegment());
        assertEquals(StreamKeys.DEFAULT_TENANT, keys.getTenant());
        assertEquals("stream:topic:t:p:0", keys.partitionStreamKey("t", 0));
        assertEquals("stream:topic:t:dlq", keys.dlqKey("t"));
        assertEquals("streaming:mq:topic:t:meta", keys.topicMetaKey("t"));
        assertEquals("streaming:mq:topic:t:partitions", keys.topicPartitionsSetKey("t"));
        assertEquals("streaming:mq:lease:t:g:3", keys.leaseKey("t", "g", 3));
        assertEquals("streaming:mq:retry:t", keys.retryBucketKey("t"));
        assertEquals("streaming:mq:retry:item:t:id", keys.retryItemKey("t", "id"));
        assertEquals("streaming:mq:topics:registry", keys.topicsRegistryKey());
        assertEquals("streaming:mq:commit:t:p:0", keys.commitFrontierKey("t", 0));
        assertEquals("streaming:mq:acks:t:p:0:m", keys.ackSetKey("t", 0, "m"));

        // static and instance builders agree
        assertEquals(StreamKeys.partitionStream("t", 0), keys.partitionStreamKey("t", 0));
        assertEquals(StreamKeys.commitFrontier("t", 0), keys.commitFrontierKey("t", 0));
    }

    @Test
    void nonDefaultTenantInsertsSegmentAfterPrefix() {
        StreamKeys keys = new StreamKeys("streaming:mq", "stream:topic", "acme");

        assertEquals(":acme", keys.tenantSegment());
        assertEquals("acme", keys.getTenant());
        assertEquals("stream:topic:acme:t:p:0", keys.partitionStreamKey("t", 0));
        assertEquals("stream:topic:acme:t:dlq", keys.dlqKey("t"));
        assertEquals("streaming:mq:acme:topic:t:meta", keys.topicMetaKey("t"));
        assertEquals("streaming:mq:acme:topic:t:partitions", keys.topicPartitionsSetKey("t"));
        assertEquals("streaming:mq:acme:lease:t:g:0", keys.leaseKey("t", "g", 0));
        assertEquals("streaming:mq:acme:retry:t", keys.retryBucketKey("t"));
        assertEquals("streaming:mq:acme:retry:item:t:id", keys.retryItemKey("t", "id"));
        assertEquals("streaming:mq:acme:topics:registry", keys.topicsRegistryKey());
        assertEquals("streaming:mq:acme:commit:t:p:0", keys.commitFrontierKey("t", 0));
        assertEquals("streaming:mq:acme:acks:t:p:0:m", keys.ackSetKey("t", 0, "m"));
    }

    @Test
    void twoTenantsNeverShareKeys() {
        StreamKeys a = new StreamKeys("streaming:mq", "stream:topic", "acme");
        StreamKeys b = new StreamKeys("streaming:mq", "stream:topic", "beta");

        assertNotEquals(a.partitionStreamKey("t", 0), b.partitionStreamKey("t", 0));
        assertNotEquals(a.dlqKey("t"), b.dlqKey("t"));
        assertNotEquals(a.topicsRegistryKey(), b.topicsRegistryKey());
    }

    // ===== tenant normalization =====

    @Test
    void normalizeTenantCollapsesBlankAndDefault() {
        assertEquals(StreamKeys.DEFAULT_TENANT, StreamKeys.normalizeTenant(null));
        assertEquals(StreamKeys.DEFAULT_TENANT, StreamKeys.normalizeTenant(""));
        assertEquals(StreamKeys.DEFAULT_TENANT, StreamKeys.normalizeTenant("  "));
        assertEquals(StreamKeys.DEFAULT_TENANT, StreamKeys.normalizeTenant("default"));
        assertEquals(StreamKeys.DEFAULT_TENANT, StreamKeys.normalizeTenant(StreamKeys.DEFAULT_TENANT));
    }

    @Test
    void normalizeTenantAcceptsBoundedNames() {
        assertEquals("acme", StreamKeys.normalizeTenant("acme"));
        assertEquals("A.b_9-d", StreamKeys.normalizeTenant("A.b_9-d"));
        assertEquals("x".repeat(64), StreamKeys.normalizeTenant("x".repeat(64)));
    }

    @Test
    void normalizeTenantRejectsAmbiguousNames() {
        // colon would make derived keys ambiguous
        assertThrows(IllegalArgumentException.class, () -> StreamKeys.normalizeTenant("a:b"));
        assertThrows(IllegalArgumentException.class, () -> StreamKeys.normalizeTenant("a b"));
        assertThrows(IllegalArgumentException.class, () -> StreamKeys.normalizeTenant("acme/ops"));
        assertThrows(IllegalArgumentException.class, () -> StreamKeys.normalizeTenant("x".repeat(65)));
        // blank is not ambiguous — it collapses to the default tenant (no key segment)
        assertEquals("default", StreamKeys.normalizeTenant(""));
        assertEquals("default", StreamKeys.normalizeTenant("  "));
    }

    @Test
    void tenantConstructorValidatesToo() {
        assertThrows(IllegalArgumentException.class, () -> new StreamKeys("p", "s", "bad:name"));
        assertDoesNotThrow(() -> new StreamKeys(null, null, "acme"));
        // null prefixes fall back to the defaults
        StreamKeys keys = new StreamKeys(null, null, "acme");
        assertEquals("streaming:mq:acme:topics:registry", keys.topicsRegistryKey());
    }

    // ===== views =====

    @Test
    void withTenantReturnsViewOnSamePrefixes() {
        StreamKeys keys = new StreamKeys("c", "s", "acme");
        StreamKeys other = keys.withTenant("beta");

        assertEquals("beta", other.getTenant());
        assertEquals("c", other.getControlPrefix());
        assertEquals("s", other.getStreamPrefix());
        assertEquals("acme", keys.getTenant());
        assertEquals("s:beta:t:p:0", other.partitionStreamKey("t", 0));
    }

    @Test
    void ofUsesOptionPrefixesAndTenant() {
        MqOptions options = MqOptions.builder().tenant("acme").build();
        StreamKeys keys = StreamKeys.of(options);

        assertEquals("acme", keys.getTenant());
        assertEquals(options.getKeyPrefix(), keys.getControlPrefix());
        assertEquals(options.getStreamKeyPrefix(), keys.getStreamPrefix());
        assertEquals("stream:topic:acme:t:p:0", keys.partitionStreamKey("t", 0));
    }

    @Test
    void ofNullOptionsFallsBackToShared() {
        assertEquals(StreamKeys.shared().partitionStreamKey("t", 0),
                StreamKeys.of(null).partitionStreamKey("t", 0));
    }

    @Test
    void processWideConfigureAcceptsTenant() {
        StreamKeys.configure("custom:control", "custom:stream", "acme");

        assertEquals("custom:stream:acme:t:p:0", StreamKeys.partitionStream("t", 0));
        assertEquals("custom:control:acme:topics:registry", StreamKeys.topicsRegistry());
    }
}
