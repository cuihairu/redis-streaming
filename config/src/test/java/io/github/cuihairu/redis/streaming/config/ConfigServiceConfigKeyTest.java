package io.github.cuihairu.redis.streaming.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigServiceConfigKeyTest {

    @Test
    void testDefaultPrefixAndSanitize() {
        ConfigServiceConfig cfg = new ConfigServiceConfig();
        // default prefix is "redis_streaming"
        String k1 = cfg.getConfigKey("group:01", "data:02");
        assertTrue(k1.startsWith(BaseRedisConfig.DEFAULT_KEY_PREFIX + ":config:"));
        assertTrue(k1.contains("group_01:data_02"), "colons should be sanitized to underscore");

        String subs = cfg.getConfigSubscribersKey("g:1", "d:2");
        assertTrue(subs.contains("config_subscribers:g_1:d_2"));

        String hist = cfg.getConfigHistoryKey("g:1", "d:2");
        assertTrue(hist.contains("config_history:g_1:d_2"));

        String ch = cfg.getConfigChangeChannelKey("g:1", "d:2");
        assertTrue(ch.contains("config_change:g_1:d_2"));
    }

    @Test
    void testDisablePrefix() {
        ConfigServiceConfig cfg = new ConfigServiceConfig("mypfx", false);
        String k = cfg.getConfigKey("g", "d");
        assertEquals("config:g:d", k);
    }

    @Test
    void testCustomPrefix() {
        ConfigServiceConfig cfg = new ConfigServiceConfig("appcfg", true);
        String k = cfg.getConfigKey("gg", "dd");
        assertTrue(k.startsWith("appcfg:"));
    }
}
