package io.github.cuihairu.redis.streaming.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigServiceConfig
 */
class ConfigServiceConfigTest {

    @Test
    void testDefaultConstructor() {
        ConfigServiceConfig config = new ConfigServiceConfig();

        assertEquals("redis_streaming", config.getKeyPrefix());
        assertTrue(config.isEnableKeyPrefix());
        assertEquals(10, config.getHistorySize());
    }

    @Test
    void testConstructorWithKeyPrefix() {
        ConfigServiceConfig config = new ConfigServiceConfig("myapp");

        assertEquals("myapp", config.getKeyPrefix());
        assertEquals(10, config.getHistorySize());
    }

    @Test
    void testConstructorWithKeyPrefixAndEnableFlag() {
        ConfigServiceConfig config = new ConfigServiceConfig("myapp", false);

        assertEquals("myapp", config.getKeyPrefix());
        assertFalse(config.isEnableKeyPrefix());
    }

    @Test
    void testSetHistorySize() {
        ConfigServiceConfig config = new ConfigServiceConfig();
        config.setHistorySize(20);

        assertEquals(20, config.getHistorySize());
    }

    @Test
    void testSetHistorySizeWithNegativeValue() {
        ConfigServiceConfig config = new ConfigServiceConfig();
        config.setHistorySize(-5);

        assertEquals(0, config.getHistorySize(), "Negative history size should be floored to 0");
    }

    @Test
    void testSetHistorySizeWithZero() {
        ConfigServiceConfig config = new ConfigServiceConfig();
        config.setHistorySize(0);

        assertEquals(0, config.getHistorySize());
    }

    @Test
    void testGetConfigKey() {
        ConfigServiceConfig config = new ConfigServiceConfig("app");

        String key = config.getConfigKey("default", "application.properties");

        assertEquals("app:config:default:application.properties", key);
    }

    @Test
    void testGetConfigKeyWithNullGroup() {
        ConfigServiceConfig config = new ConfigServiceConfig("app");

        String key = config.getConfigKey(null, "data1");

        assertEquals("app:config::data1", key);
    }

    @Test
    void testGetConfigKeyWithNullDataId() {
        ConfigServiceConfig config = new ConfigServiceConfig("app");

        String key = config.getConfigKey("group1", null);

        assertEquals("app:config:group1:", key);
    }

    @Test
    void testGetConfigKeyWithNullBoth() {
        ConfigServiceConfig config = new ConfigServiceConfig("app");

        String key = config.getConfigKey(null, null);

        assertEquals("app:config::", key);
    }

    @Test
    void testGetConfigKeyWithColonInGroup() {
        ConfigServiceConfig config = new ConfigServiceConfig("app");

        String key = config.getConfigKey("group:with:colons", "data1");

        assertEquals("app:config:group_with_colons:data1", key);
    }

    @Test
    void testGetConfigKeyWithColonInDataId() {
        ConfigServiceConfig config = new ConfigServiceConfig("app");

        String key = config.getConfigKey("group1", "data:id:with:colons");

        assertEquals("app:config:group1:data_id_with_colons", key);
    }

    @Test
    void testGetConfigKeyWithSpaces() {
        ConfigServiceConfig config = new ConfigServiceConfig("app");

        String key = config.getConfigKey(" group with spaces ", " data id ");

        assertEquals("app:config:group with spaces:data id", key);
    }

    @Test
    void testGetConfigSubscribersKey() {
        ConfigServiceConfig config = new ConfigServiceConfig("app");

        String key = config.getConfigSubscribersKey("default", "app-config");

        assertEquals("app:config_subscribers:default:app-config", key);
    }

    @Test
    void testGetConfigHistoryKey() {
        ConfigServiceConfig config = new ConfigServiceConfig("app");

        String key = config.getConfigHistoryKey("prod", "database.yaml");

        assertEquals("app:config_history:prod:database.yaml", key);
    }

    @Test
    void testGetConfigChangeChannelKey() {
        ConfigServiceConfig config = new ConfigServiceConfig("app");

        String key = config.getConfigChangeChannelKey("test", "config.json");

        assertEquals("app:config_change:test:config.json", key);
    }

    @Test
    void testGetConfigKeyWithoutPrefix() {
        ConfigServiceConfig config = new ConfigServiceConfig("app");
        config.setEnableKeyPrefix(false);

        String key = config.getConfigKey("group1", "data1");

        assertEquals("config:group1:data1", key);
    }

    @Test
    void testKeySanitizationConsistentAcrossMethods() {
        ConfigServiceConfig config = new ConfigServiceConfig("myapp");
        String group = "test:group";
        String dataId = "test:data";

        String configKey = config.getConfigKey(group, dataId);
        String subsKey = config.getConfigSubscribersKey(group, dataId);
        String histKey = config.getConfigHistoryKey(group, dataId);
        String channelKey = config.getConfigChangeChannelKey(group, dataId);

        assertTrue(configKey.contains("test_group"));
        assertTrue(configKey.contains("test_data"));
        assertTrue(subsKey.contains("test_group"));
        assertTrue(subsKey.contains("test_data"));
        assertTrue(histKey.contains("test_group"));
        assertTrue(histKey.contains("test_data"));
        assertTrue(channelKey.contains("test_group"));
        assertTrue(channelKey.contains("test_data"));
    }

    @Test
    void testDifferentKeyPrefixes() {
        ConfigServiceConfig config1 = new ConfigServiceConfig("app1");
        ConfigServiceConfig config2 = new ConfigServiceConfig("app2");

        String key1 = config1.getConfigKey("group", "data");
        String key2 = config2.getConfigKey("group", "data");

        assertEquals("app1:config:group:data", key1);
        assertEquals("app2:config:group:data", key2);
    }

    @Test
    void testSpecialCharactersInGroupAndDataId() {
        ConfigServiceConfig config = new ConfigServiceConfig("app");

        // Test with special characters - only colons are replaced with underscore
        String key = config.getConfigKey("group:test", "data:id");

        assertTrue(key.contains("group_test"));
        assertTrue(key.contains("data_id"));
        // Verify no colons from input remain in the sanitized parts
        assertFalse(key.contains("app:config:group:test:data:id")); // would have unescaped colons
        // Correct format: prefix:type:sanitized_group:sanitized_dataId
        assertTrue(key.contains("app:config:group_test:data_id"));
    }

    @Test
    void testEmptyStrings() {
        ConfigServiceConfig config = new ConfigServiceConfig("app");

        String key = config.getConfigKey("", "");

        assertEquals("app:config::", key);
    }

    @Test
    void testComplexDataIdWithExtension() {
        ConfigServiceConfig config = new ConfigServiceConfig("myapp");

        String key = config.getConfigKey("production", "application-prod.yml");

        assertEquals("myapp:config:production:application-prod.yml", key);
    }

    @Test
    void testGetConfigSubscribersKeyWithoutPrefix() {
        ConfigServiceConfig config = new ConfigServiceConfig("myapp");
        config.setEnableKeyPrefix(false);

        String key = config.getConfigSubscribersKey("group1", "data1");

        assertEquals("config_subscribers:group1:data1", key);
    }

    @Test
    void testGetConfigHistoryKeyWithoutPrefix() {
        ConfigServiceConfig config = new ConfigServiceConfig("myapp");
        config.setEnableKeyPrefix(false);

        String key = config.getConfigHistoryKey("group1", "data1");

        assertEquals("config_history:group1:data1", key);
    }

    @Test
    void testGetConfigChangeChannelKeyWithoutPrefix() {
        ConfigServiceConfig config = new ConfigServiceConfig("myapp");
        config.setEnableKeyPrefix(false);

        String key = config.getConfigChangeChannelKey("group1", "data1");

        assertEquals("config_change:group1:data1", key);
    }

    @Test
    void testInheritanceFromBaseRedisConfig() {
        ConfigServiceConfig config = new ConfigServiceConfig();

        // Can use formatKey from parent class
        String key = config.formatKey("custom:%s", "value");

        assertEquals("redis_streaming:custom:value", key);
    }

    @Test
    void testLargeHistorySize() {
        ConfigServiceConfig config = new ConfigServiceConfig();
        config.setHistorySize(10000);

        assertEquals(10000, config.getHistorySize());
    }

    @Test
    void testKeyWithDots() {
        ConfigServiceConfig config = new ConfigServiceConfig("app");

        String key = config.getConfigKey("com.example.app", "com.example.config");

        assertEquals("app:config:com.example.app:com.example.config", key);
    }
}
