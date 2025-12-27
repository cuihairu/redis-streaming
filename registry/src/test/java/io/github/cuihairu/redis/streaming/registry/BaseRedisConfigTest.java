package io.github.cuihairu.redis.streaming.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BaseRedisConfigTest {

    @Test
    public void testDefaultRegistryKeysUseDefaultPrefix() {
        BaseRedisConfig cfg = new BaseRedisConfig();
        assertEquals(BaseRedisConfig.DEFAULT_KEY_PREFIX, cfg.getKeyPrefix());
        assertNotNull(cfg.getRegistryKeys());
        assertEquals(BaseRedisConfig.DEFAULT_KEY_PREFIX, cfg.getRegistryKeys().getKeyPrefix());
    }

    @Test
    public void testSetKeyPrefixRebuildsRegistryKeys() {
        BaseRedisConfig cfg = new BaseRedisConfig("p1");
        assertEquals("p1", cfg.getRegistryKeys().getKeyPrefix());

        cfg.setKeyPrefix("p2");
        assertEquals("p2", cfg.getKeyPrefix());
        assertEquals("p2", cfg.getRegistryKeys().getKeyPrefix());
    }

    @Test
    public void testFormatKeyWithPrefix() {
        BaseRedisConfig cfg = new BaseRedisConfig("pfx", true);
        assertEquals("pfx:svc:1", cfg.formatKey("svc:%s", 1));
    }

    @Test
    public void testFormatKeyWithoutPrefix() {
        BaseRedisConfig cfg = new BaseRedisConfig("pfx", true);
        cfg.setEnableKeyPrefix(false);
        assertEquals("svc:1", cfg.formatKey("svc:%s", 1));
    }

    @Test
    public void testFormatKeyWhenPrefixEmpty() {
        BaseRedisConfig cfg = new BaseRedisConfig("", true);
        assertEquals("svc:1", cfg.formatKey("svc:%s", 1));
    }
}

