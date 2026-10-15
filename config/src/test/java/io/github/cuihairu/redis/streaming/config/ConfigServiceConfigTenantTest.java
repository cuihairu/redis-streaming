package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.core.utils.Tenant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tenant-scoped config keys: a non-default tenant inserts a single {@code :tenant}
 * segment right after the key prefix, and the default tenant keeps the legacy keys
 * byte-identical (zero migration).
 */
class ConfigServiceConfigTenantTest {

    @Test
    void defaultTenantKeepsLegacyKeys() {
        ConfigServiceConfig cfg = new ConfigServiceConfig("redis_streaming", true);
        assertEquals("default", cfg.getTenant());
        assertEquals("redis_streaming:config:g:d", cfg.getConfigKey("g", "d"));
        assertEquals("redis_streaming:config_subscribers:g:d", cfg.getConfigSubscribersKey("g", "d"));
        assertEquals("redis_streaming:config_history:g:d", cfg.getConfigHistoryKey("g", "d"));
        assertEquals("redis_streaming:config_change:g:d", cfg.getConfigChangeChannelKey("g", "d"));
    }

    @Test
    void nonDefaultTenantInsertsSegmentAfterPrefix() {
        ConfigServiceConfig cfg = new ConfigServiceConfig("redis_streaming", true, "acme");
        assertEquals("acme", cfg.getTenant());
        assertEquals("redis_streaming:acme:config:g:d", cfg.getConfigKey("g", "d"));
        assertEquals("redis_streaming:acme:config_subscribers:g:d", cfg.getConfigSubscribersKey("g", "d"));
        assertEquals("redis_streaming:acme:config_history:g:d", cfg.getConfigHistoryKey("g", "d"));
        assertEquals("redis_streaming:acme:config_change:g:d", cfg.getConfigChangeChannelKey("g", "d"));
    }

    @Test
    void setterSwapsTheTenantAndBlankFallsBackToDefault() {
        ConfigServiceConfig cfg = new ConfigServiceConfig("redis_streaming", true);
        cfg.setTenant("acme");
        assertEquals("redis_streaming:acme:config:g:d", cfg.getConfigKey("g", "d"));

        cfg.setTenant("  ");
        assertEquals(Tenant.DEFAULT_TENANT, cfg.getTenant());
        assertEquals("redis_streaming:config:g:d", cfg.getConfigKey("g", "d"));
    }

    @Test
    void prefixToggleAndCustomPrefixInteractWithTheTenant() {
        // prefix disabled: the base contract drops the prefix (and therefore the tenant)
        ConfigServiceConfig off = new ConfigServiceConfig("redis_streaming", false, "acme");
        assertEquals("config:g:d", off.getConfigKey("g", "d"));

        ConfigServiceConfig custom = new ConfigServiceConfig("appcfg", true, "team-a.1");
        assertEquals("appcfg:team-a.1:config:g:d", custom.getConfigKey("g", "d"));
    }

    @Test
    void twoTenantsNeverShareAConfigKey() {
        ConfigServiceConfig acme = new ConfigServiceConfig("redis_streaming", true, "acme");
        ConfigServiceConfig beta = new ConfigServiceConfig("redis_streaming", true, "beta");
        assertEquals("redis_streaming:acme:config:g:d", acme.getConfigKey("g", "d"));
        assertEquals("redis_streaming:beta:config:g:d", beta.getConfigKey("g", "d"));
        assertTrue(!acme.getConfigKey("g", "d").equals(beta.getConfigKey("g", "d")));
    }

    @Test
    void tenantNameGrammarIsSharedWithOtherModules() {
        ConfigServiceConfig cfg = new ConfigServiceConfig();
        cfg.setTenant("acme");
        assertThrows(IllegalArgumentException.class, () -> cfg.setTenant("a:b"));
        assertThrows(IllegalArgumentException.class, () -> cfg.setTenant("has space"));
        assertThrows(IllegalArgumentException.class, () -> new ConfigServiceConfig("p", true, "a:b"));
    }
}
