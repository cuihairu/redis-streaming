package io.github.cuihairu.redis.streaming.core.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared tenant contract: every module that builds Redis keys uses the same
 * default tenant, the same name grammar and the same key segment, so a tenant
 * name is valid (and equally scoped) in all of them.
 */
class TenantTest {

    @Test
    void defaultTenantHasNoKeySegment() {
        Tenant t = new Tenant(null);
        assertEquals("default", Tenant.DEFAULT_TENANT);
        assertEquals("default", t.getName());
        assertEquals("", t.segment());
        assertTrue(t.isDefault());
        assertEquals("registry", t.scopedPrefix("registry"));
    }

    @Test
    void nonDefaultTenantInsertsSegmentAfterPrefix() {
        Tenant t = new Tenant("acme");
        assertEquals("acme", t.getName());
        assertEquals(":acme", t.segment());
        assertFalse(t.isDefault());
        assertEquals("registry:acme", t.scopedPrefix("registry"));
        assertEquals("redis_streaming:acme", t.scopedPrefix("redis_streaming"));
    }

    @Test
    void normalizeCollapsesBlankAndRejectsUnsafeNames() {
        assertEquals("default", Tenant.normalize(null));
        assertEquals("default", Tenant.normalize(""));
        assertEquals("default", Tenant.normalize("   "));
        assertEquals("default", Tenant.normalize("default"));
        assertEquals("team-a.1", Tenant.normalize("team-a.1"));
        assertEquals("a", Tenant.normalize("a"));
        assertEquals("a".repeat(64), Tenant.normalize("a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> Tenant.normalize("a:b"));
        assertThrows(IllegalArgumentException.class, () -> Tenant.normalize("has space"));
        assertThrows(IllegalArgumentException.class, () -> Tenant.normalize("x".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> Tenant.normalize("中文"));
    }

    @Test
    void staticHelpersFollowTheSameRule() {
        assertEquals("", Tenant.segment(null));
        assertEquals("", Tenant.segment("default"));
        assertEquals(":acme", Tenant.segment("acme"));
        assertEquals("registry:acme", Tenant.scopedPrefix("registry", "acme"));
        assertEquals("registry", Tenant.scopedPrefix("registry", null));
    }

    @Test
    void scopedKeyNamespacesACallerGivenNameUnderThePrefix() {
        assertEquals("app:acme:orders", Tenant.scopedKey("app", "acme", "orders"));
        assertEquals("app:acme:orders", new Tenant("acme").scopedKey("app", "orders"));
        assertEquals("app:orders", Tenant.scopedKey("app", null, "orders"));
        assertEquals("app:orders", Tenant.scopedKey("app", "default", "orders"));
    }

    @Test
    void scopedKeyWithoutPrefixKeepsTheCallerGivenName() {
        assertEquals("orders", Tenant.scopedKey(null, "acme", "orders"));
        assertEquals("orders", Tenant.scopedKey("", "acme", "orders"));
        assertEquals("orders", Tenant.scopedKey("  ", "acme", "orders"));
        assertEquals("orders", Tenant.scopedKey(null, null, "orders"));
        assertEquals("orders", new Tenant("acme").scopedKey(null, "orders"));
    }

    @Test
    void scopedKeyStillValidatesTheTenantName() {
        assertThrows(IllegalArgumentException.class, () -> Tenant.scopedKey("app", "a:b", "orders"));
    }

    @Test
    void equalityIsNameBased() {
        assertEquals(new Tenant("acme"), new Tenant("acme"));
        assertEquals(new Tenant("acme").hashCode(), new Tenant("acme").hashCode());
        assertEquals(new Tenant(null), new Tenant("default"));
        assertNotEquals(new Tenant("acme"), new Tenant("beta"));
        assertNotEquals(new Tenant("acme"), null);
        assertNotEquals(new Tenant("acme"), "acme");
        assertEquals("Tenant{acme}", new Tenant("acme").toString());
    }
}
