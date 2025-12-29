package io.github.cuihairu.redis.streaming.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BaseRedisConfig
 */
class BaseRedisConfigTest {

    @Test
    void testDefaultConstructor() {
        BaseRedisConfig config = new BaseRedisConfig();

        assertEquals("redis_streaming", config.getKeyPrefix());
        assertTrue(config.isEnableKeyPrefix());
    }

    @Test
    void testConstructorWithKeyPrefix() {
        BaseRedisConfig config = new BaseRedisConfig("custom_prefix");

        assertEquals("custom_prefix", config.getKeyPrefix());
        assertTrue(config.isEnableKeyPrefix());
    }

    @Test
    void testConstructorWithKeyPrefixAndEnableFlag() {
        BaseRedisConfig config = new BaseRedisConfig("my_prefix", false);

        assertEquals("my_prefix", config.getKeyPrefix());
        assertFalse(config.isEnableKeyPrefix());
    }

    @Test
    void testSetKeyPrefix() {
        BaseRedisConfig config = new BaseRedisConfig();
        config.setKeyPrefix("new_prefix");

        assertEquals("new_prefix", config.getKeyPrefix());
    }

    @Test
    void testSetEnableKeyPrefix() {
        BaseRedisConfig config = new BaseRedisConfig();
        config.setEnableKeyPrefix(false);

        assertFalse(config.isEnableKeyPrefix());
    }

    @Test
    void testFormatKeyWithPrefixEnabled() {
        BaseRedisConfig config = new BaseRedisConfig("app");

        String result = config.formatKey("config:%s", "data1");

        assertEquals("app:config:data1", result);
    }

    @Test
    void testFormatKeyWithPrefixDisabled() {
        BaseRedisConfig config = new BaseRedisConfig("app");
        config.setEnableKeyPrefix(false);

        String result = config.formatKey("config:%s", "data1");

        assertEquals("config:data1", result);
    }

    @Test
    void testFormatKeyWithNullPrefix() {
        BaseRedisConfig config = new BaseRedisConfig();
        config.setKeyPrefix(null);

        String result = config.formatKey("config:%s", "data1");

        // With null prefix and enableKeyPrefix=true, should skip prefix
        assertEquals("config:data1", result);
    }

    @Test
    void testFormatKeyWithEmptyPrefix() {
        BaseRedisConfig config = new BaseRedisConfig("");
        config.setEnableKeyPrefix(true);

        String result = config.formatKey("config:%s", "data1");

        // Empty string is treated as falsy
        assertEquals("config:data1", result);
    }

    @Test
    void testFormatKeyWithMultipleArgs() {
        BaseRedisConfig config = new BaseRedisConfig("streaming");

        String result = config.formatKey("service:%s:%s", "user-service", "config");

        assertEquals("streaming:service:user-service:config", result);
    }

    @Test
    void testFormatKeyWithNoArgs() {
        BaseRedisConfig config = new BaseRedisConfig("app");

        String result = config.formatKey("global_config");

        assertEquals("app:global_config", result);
    }

    @Test
    void testFormatKeyWithNumericArgs() {
        BaseRedisConfig config = new BaseRedisConfig("app");

        String result = config.formatKey("partition:%d", 123);

        assertEquals("app:partition:123", result);
    }

    @Test
    void testFormatKeyWithMixedArgs() {
        BaseRedisConfig config = new BaseRedisConfig("data");

        String result = config.formatKey("user:%s:age:%d", "john", 30);

        assertEquals("data:user:john:age:30", result);
    }

    @Test
    void testDefaultKeyPrefixConstant() {
        assertEquals("redis_streaming", BaseRedisConfig.DEFAULT_KEY_PREFIX);
    }

    @Test
    void testChainedSetters() {
        BaseRedisConfig config = new BaseRedisConfig();
        config.setKeyPrefix("test");
        config.setEnableKeyPrefix(false);

        assertEquals("test", config.getKeyPrefix());
        assertFalse(config.isEnableKeyPrefix());
    }

    @Test
    void testComplexKeyPatterns() {
        BaseRedisConfig config = new BaseRedisConfig("myapp");

        String result = config.formatKey("config:%s:env:%s:v%s", "data1", "prod", "2");

        assertEquals("myapp:config:data1:env:prod:v2", result);
    }

    @Test
    void testSpecialCharactersInPrefix() {
        BaseRedisConfig config = new BaseRedisConfig("my_app-v1");

        String result = config.formatKey("key:%s", "value");

        assertEquals("my_app-v1:key:value", result);
    }

    @Test
    void testFormatKeyIdempotency() {
        BaseRedisConfig config = new BaseRedisConfig("app");

        String result1 = config.formatKey("test:%s", "key1");
        String result2 = config.formatKey("test:%s", "key1");

        assertEquals(result1, result2);
    }

    @Test
    void testKeyPrefixWithColon() {
        BaseRedisConfig config = new BaseRedisConfig("prefix:with:colons");

        String result = config.formatKey("suffix");

        assertEquals("prefix:with:colons:suffix", result);
    }

    @Test
    void testEnableKeyPrefixToggle() {
        BaseRedisConfig config = new BaseRedisConfig("app");

        config.setEnableKeyPrefix(false);
        assertEquals("config:test", config.formatKey("config:%s", "test"));

        config.setEnableKeyPrefix(true);
        assertEquals("app:config:test", config.formatKey("config:%s", "test"));
    }

    @Test
    void testEmptyKeyPattern() {
        BaseRedisConfig config = new BaseRedisConfig("app");

        String result = config.formatKey("", "arg1");

        // formatKey with empty pattern returns just prefix (args are not processed)
        assertEquals("app:", result);
    }

    @Test
    void testVeryLongKeyPrefix() {
        String longPrefix = "a".repeat(100);
        BaseRedisConfig config = new BaseRedisConfig(longPrefix);

        String result = config.formatKey("key");

        assertTrue(result.startsWith(longPrefix));
        assertTrue(result.endsWith(":key"));
    }
}
