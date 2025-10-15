package io.github.cuihairu.redis.streaming.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaseRedisConfigFormatKeyTest {

    @Test
    void testFormatWithPrefixEnabled() {
        BaseRedisConfig c = new BaseRedisConfig();
        c.setKeyPrefix("my_prefix");
        String k = c.formatKey("a:%s:%s", "b", "c");
        assertEquals("my_prefix:a:b:c", k);
    }

    @Test
    void testFormatWithPrefixDisabled() {
        BaseRedisConfig c = new BaseRedisConfig("pre", false);
        String k = c.formatKey("x:%s", 1);
        assertEquals("x:1", k);
    }

    @Test
    void testNullEmptyPrefixHandling() {
        BaseRedisConfig c = new BaseRedisConfig(null);
        c.setEnableKeyPrefix(true);
        // keyPrefix defaults to null; format should not prepend when keyPrefix is null/empty
        String k = c.formatKey("k:%s", "v");
        assertEquals("k:v", k);
    }
}
