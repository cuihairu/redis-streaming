package io.github.cuihairu.redis.streaming.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BaseRedisConfig
 */
class BaseRedisConfigTest {

    @Test
    void testDefaultConstructor() {
        // Given & When
        BaseRedisConfig config = new BaseRedisConfig();

        // Then
        assertEquals(BaseRedisConfig.DEFAULT_KEY_PREFIX, config.getKeyPrefix());
        assertTrue(config.isEnableKeyPrefix());
    }

    @Test
    void testConstructorWithKeyPrefix() {
        // Given
        String customPrefix = "custom_prefix";

        // When
        BaseRedisConfig config = new BaseRedisConfig(customPrefix);

        // Then
        assertEquals(customPrefix, config.getKeyPrefix());
        assertTrue(config.isEnableKeyPrefix());
    }

    @Test
    void testConstructorWithKeyPrefixAndEnableFlag() {
        // Given
        String customPrefix = "custom_prefix";
        boolean enableKeyPrefix = false;

        // When
        BaseRedisConfig config = new BaseRedisConfig(customPrefix, enableKeyPrefix);

        // Then
        assertEquals(customPrefix, config.getKeyPrefix());
        assertEquals(enableKeyPrefix, config.isEnableKeyPrefix());
    }

    @Test
    void testSetKeyPrefix() {
        // Given
        BaseRedisConfig config = new BaseRedisConfig();
        String newPrefix = "new_prefix";

        // When
        config.setKeyPrefix(newPrefix);

        // Then
        assertEquals(newPrefix, config.getKeyPrefix());
    }

    @Test
    void testSetEnableKeyPrefix() {
        // Given
        BaseRedisConfig config = new BaseRedisConfig();

        // When
        config.setEnableKeyPrefix(false);

        // Then
        assertFalse(config.isEnableKeyPrefix());
    }

    @Test
    void testFormatKeyWithPrefixEnabled() {
        // Given
        BaseRedisConfig config = new BaseRedisConfig("myapp");
        String keyPattern = "user:%d";

        // When
        String formattedKey = config.formatKey(keyPattern, 123);

        // Then
        assertEquals("myapp:user:123", formattedKey);
    }

    @Test
    void testFormatKeyWithPrefixDisabled() {
        // Given
        BaseRedisConfig config = new BaseRedisConfig("myapp");
        config.setEnableKeyPrefix(false);
        String keyPattern = "user:%d";

        // When
        String formattedKey = config.formatKey(keyPattern, 123);

        // Then
        assertEquals("user:123", formattedKey);
    }

    @Test
    void testFormatKeyWithEmptyPrefix() {
        // Given
        BaseRedisConfig config = new BaseRedisConfig("");
        String keyPattern = "user:%d";

        // When
        String formattedKey = config.formatKey(keyPattern, 123);

        // Then
        assertEquals("user:123", formattedKey);
    }

    @Test
    void testFormatKeyWithNullPrefix() {
        // Given
        BaseRedisConfig config = new BaseRedisConfig();
        config.setKeyPrefix(null);
        String keyPattern = "user:%d";

        // When
        String formattedKey = config.formatKey(keyPattern, 123);

        // Then
        assertEquals("user:123", formattedKey);
    }

    @Test
    void testFormatKeyWithMultipleArgs() {
        // Given
        BaseRedisConfig config = new BaseRedisConfig("app");

        // When
        String formattedKey = config.formatKey("user:%s:status:%d", "john", 1);

        // Then
        assertEquals("app:user:john:status:1", formattedKey);
    }

    @Test
    void testFormatKeyWithNoArgs() {
        // Given
        BaseRedisConfig config = new BaseRedisConfig("app");
        String keyPattern = "all_users";

        // When
        String formattedKey = config.formatKey(keyPattern);

        // Then
        assertEquals("app:all_users", formattedKey);
    }

    @Test
    void testFormatKeyWithNoArgsAndPrefixDisabled() {
        // Given
        BaseRedisConfig config = new BaseRedisConfig("app");
        config.setEnableKeyPrefix(false);
        String keyPattern = "all_users";

        // When
        String formattedKey = config.formatKey(keyPattern);

        // Then
        assertEquals("all_users", formattedKey);
    }

    @Test
    void testDefaultKeyPrefixConstant() {
        // Given & When & Then
        assertEquals("redis_streaming", BaseRedisConfig.DEFAULT_KEY_PREFIX);
    }

    @Test
    void testGetKeyPrefix() {
        // Given
        BaseRedisConfig config = new BaseRedisConfig("test_prefix");

        // When
        String keyPrefix = config.getKeyPrefix();

        // Then
        assertEquals("test_prefix", keyPrefix);
    }
}
