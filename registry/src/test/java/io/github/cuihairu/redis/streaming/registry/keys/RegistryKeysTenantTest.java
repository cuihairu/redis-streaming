package io.github.cuihairu.redis.streaming.registry.keys;

import io.github.cuihairu.redis.streaming.registry.BaseRedisConfig;
import io.github.cuihairu.redis.streaming.registry.NamingServiceConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tenant-scoped registry keys: a non-default tenant inserts a
 * {@code :tenant} segment right after the key prefix, the default tenant
 * yields byte-identical legacy keys (zero migration cost). The tenant
 * contract mirrors {@code StreamKeys}: 1..64 chars of {@code [A-Za-z0-9._-]}.
 */
class RegistryKeysTenantTest {

    @Test
    void defaultTenantKeepsLegacyKeysByteIdentical() {
        RegistryKeys keys = new RegistryKeys("registry");
        assertEquals("default", keys.getTenant());
        assertEquals("", keys.tenantSegment());
        assertEquals("registry", keys.getEffectivePrefix());
        assertEquals("registry:services", keys.getServicesIndexKey());
        assertEquals("registry:services:svc:heartbeats", keys.getServiceHeartbeatsKey("svc"));
        assertEquals("registry:services:svc:instance:i1", keys.getServiceInstanceKey("svc", "i1"));
        assertEquals("registry:services:svc:changes", keys.getServiceChangeChannelKey("svc"));
    }

    @Test
    void nonDefaultTenantInsertsSegmentAfterPrefix() {
        RegistryKeys keys = new RegistryKeys("registry", "acme");
        assertEquals("acme", keys.getTenant());
        assertEquals(":acme", keys.tenantSegment());
        assertEquals("registry:acme", keys.getEffectivePrefix());
        assertEquals("registry:acme:services", keys.getServicesIndexKey());
        assertEquals("registry:acme:services:svc:heartbeats", keys.getServiceHeartbeatsKey("svc"));
        assertEquals("registry:acme:services:svc:instance:i1", keys.getServiceInstanceKey("svc", "i1"));
        assertEquals("registry:acme:services:svc:changes", keys.getServiceChangeChannelKey("svc"));
    }

    @Test
    void twoTenantsDoNotShareKeys() {
        RegistryKeys acme = new RegistryKeys("registry", "acme");
        RegistryKeys beta = new RegistryKeys("registry", "beta");
        assertEquals("registry:acme:services", acme.getServicesIndexKey());
        assertEquals("registry:beta:services", beta.getServicesIndexKey());
        assertFalse(acme.getServicesIndexKey().equals(beta.getServicesIndexKey()));
        assertFalse(acme.isRegistryKey("registry:beta:services:svc:instance:i1"));
        assertFalse(beta.isRegistryKey("registry:acme:services"));
    }

    @Test
    void normalizeTenantCollapsesBlankAndRejectsColon() {
        assertEquals("default", RegistryKeys.normalizeTenant(null));
        assertEquals("default", RegistryKeys.normalizeTenant(""));
        assertEquals("default", RegistryKeys.normalizeTenant("   "));
        assertEquals("default", RegistryKeys.normalizeTenant("default"));
        assertEquals("team-a.1", RegistryKeys.normalizeTenant("team-a.1"));
        assertThrows(IllegalArgumentException.class, () -> RegistryKeys.normalizeTenant("a:b"));
        assertThrows(IllegalArgumentException.class, () -> RegistryKeys.normalizeTenant("has space"));
        assertThrows(IllegalArgumentException.class, () -> RegistryKeys.normalizeTenant("x".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> new RegistryKeys("registry", "a:b"));
    }

    @Test
    void blankTenantFallsBackToLegacyLayout() {
        RegistryKeys keys = new RegistryKeys("registry", "  ");
        assertEquals("registry:services:svc:instance:i1", keys.getServiceInstanceKey("svc", "i1"));
    }

    @Test
    void withTenantSwapsTheTenantOfTheView() {
        RegistryKeys acme = new RegistryKeys("registry", "acme").withTenant("beta");
        assertEquals("registry:beta:services:svc:heartbeats", acme.getServiceHeartbeatsKey("svc"));
        assertEquals("registry:acme:services", new RegistryKeys("registry", "acme").getServicesIndexKey());
    }

    @Test
    void extractorsWorkOnTenantScopedKeys() {
        RegistryKeys keys = new RegistryKeys("registry", "acme");
        assertEquals("svc", keys.extractServiceNameFromInstanceKey("registry:acme:services:svc:instance:i1"));
        assertEquals("i1", keys.extractInstanceIdFromInstanceKey("registry:acme:services:svc:instance:i1"));
        assertEquals("svc", keys.extractServiceNameFromHeartbeatsKey("registry:acme:services:svc:heartbeats"));
    }

    @Test
    void extractorsRejectOtherTenantsAndMalformedKeys() {
        RegistryKeys keys = new RegistryKeys("registry", "acme");
        assertNullForOtherTenant(keys, "registry:beta:services:svc:instance:i1");
        assertNullForOtherTenant(keys, "registry:services:svc:instance:i1");
        // legacy shapes must still be refused for the same wrong-format reasons
        assertFalse(keys.isRegistryKey("other:acme:services:svc:instance:i1"));
    }

    @Test
    void extractorsStillWorkOnLegacyKeysWithTenantAwareView() {
        // a tenant view parses legacy-shaped keys only when the shape matches after the prefix
        RegistryKeys keys = new RegistryKeys("registry");
        assertEquals("svc", keys.extractServiceNameFromInstanceKey("registry:services:svc:instance:i1"));
        assertEquals("i1", keys.extractInstanceIdFromInstanceKey("registry:services:svc:instance:i1"));
        assertEquals("svc", keys.extractServiceNameFromHeartbeatsKey("registry:services:svc:heartbeats"));
        assertNullShape(keys);
    }

    @Test
    void configPropagatesTenantIntoRegistryKeys() {
        NamingServiceConfig config = new NamingServiceConfig("registry");
        config.setTenant("acme");
        assertEquals("acme", config.getTenant());
        assertEquals("acme", config.getRegistryKeys().getTenant());
        assertEquals("registry:acme:services", config.getRegistryKeys().getServicesIndexKey());

        // changing the prefix keeps the tenant
        config.setKeyPrefix("custom");
        assertEquals("custom:acme:services", config.getRegistryKeys().getServicesIndexKey());

        // and the tenant survives a prefix round-trip
        BaseRedisConfig triple = new BaseRedisConfig("registry", true, "acme");
        assertEquals("acme", triple.getTenant());
        assertEquals("acme", triple.getRegistryKeys().getTenant());
    }

    private void assertNullForOtherTenant(RegistryKeys keys, String foreignKey) {
        if (keys.isRegistryKey(foreignKey)) {
            // foreign keys must not be treated as this tenant's keys at all
            throw new AssertionError("foreign key accepted: " + foreignKey);
        }
    }

    private void assertNullShape(RegistryKeys keys) {
        assertFalse(keys.isRegistryKey("other:services:svc:instance:i1"));
        assertFalse(keys.isRegistryKey(null));
        assertFalse(keys.isRegistryKey(""));
    }
}
