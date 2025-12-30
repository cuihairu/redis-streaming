package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigEntryCodec
 */
class ConfigEntryCodecTest {

    @Test
    void testToMapWithAllFields() {
        long updateTime = System.currentTimeMillis();
        long createTime = updateTime - 10000;

        Map<String, String> map = ConfigEntryCodec.toMap(
                "test-content", "1.0.0", "test-description", updateTime, createTime
        );

        assertEquals("test-content", map.get("content"));
        assertEquals("1.0.0", map.get("version"));
        assertEquals("test-description", map.get("description"));
        assertEquals(String.valueOf(updateTime), map.get("updateTime"));
        assertEquals(String.valueOf(createTime), map.get("createTime"));
    }

    @Test
    void testToMapWithNullContent() {
        long updateTime = System.currentTimeMillis();

        Map<String, String> map = ConfigEntryCodec.toMap(
                null, "1.0.0", "test-description", updateTime, null
        );

        assertFalse(map.containsKey("content"));
        assertEquals("1.0.0", map.get("version"));
        assertEquals("test-description", map.get("description"));
        assertEquals(String.valueOf(updateTime), map.get("updateTime"));
        assertFalse(map.containsKey("createTime"));
    }

    @Test
    void testToMapWithEmptyDescription() {
        long updateTime = System.currentTimeMillis();

        Map<String, String> map = ConfigEntryCodec.toMap(
                "test-content", "1.0.0", "", updateTime, null
        );

        assertEquals("test-content", map.get("content"));
        assertEquals("1.0.0", map.get("version"));
        assertFalse(map.containsKey("description"));
    }

    @Test
    void testToMapWithNullDescription() {
        long updateTime = System.currentTimeMillis();

        Map<String, String> map = ConfigEntryCodec.toMap(
                "test-content", "1.0.0", null, updateTime, null
        );

        assertEquals("test-content", map.get("content"));
        assertEquals("1.0.0", map.get("version"));
        assertFalse(map.containsKey("description"));
    }

    @Test
    void testToMapWithNullVersion() {
        long updateTime = System.currentTimeMillis();

        Map<String, String> map = ConfigEntryCodec.toMap(
                "test-content", null, "test-description", updateTime, null
        );

        assertEquals("test-content", map.get("content"));
        assertFalse(map.containsKey("version"));
        assertEquals("test-description", map.get("description"));
    }

    @Test
    void testParseInfoWithAllFields() {
        long createTime = System.currentTimeMillis() - 10000;
        long updateTime = System.currentTimeMillis();

        Map<String, String> map = Map.of(
                "content", "test-content",
                "version", "1.0.0",
                "description", "test-description",
                "createTime", String.valueOf(createTime),
                "updateTime", String.valueOf(updateTime)
        );

        ConfigInfo info = ConfigEntryCodec.parseInfo("test-data-id", "test-group", map);

        assertEquals("test-data-id", info.getDataId());
        assertEquals("test-group", info.getGroup());
        assertEquals("test-content", info.getContent());
        assertEquals("1.0.0", info.getVersion());
        assertEquals("test-description", info.getDescription());
        assertNotNull(info.getCreateTime());
        assertNotNull(info.getUpdateTime());
    }

    @Test
    void testParseInfoWithNullMap() {
        ConfigInfo info = ConfigEntryCodec.parseInfo("test-data-id", "test-group", null);

        assertNull(info);
    }

    @Test
    void testParseInfoWithEmptyMap() {
        ConfigInfo info = ConfigEntryCodec.parseInfo("test-data-id", "test-group", Map.of());

        assertNotNull(info);
        assertEquals("test-data-id", info.getDataId());
        assertEquals("test-group", info.getGroup());
        assertNull(info.getContent());
        assertNull(info.getVersion());
        assertNull(info.getDescription());
        assertNull(info.getCreateTime());
        assertNull(info.getUpdateTime());
    }

    @Test
    void testParseInfoWithPartialFields() {
        long updateTime = System.currentTimeMillis();

        Map<String, String> map = Map.of(
                "content", "test-content",
                "updateTime", String.valueOf(updateTime)
        );

        ConfigInfo info = ConfigEntryCodec.parseInfo("test-data-id", "test-group", map);

        assertEquals("test-content", info.getContent());
        assertNull(info.getVersion());
        assertNull(info.getDescription());
        assertNotNull(info.getUpdateTime());
        assertNull(info.getCreateTime());
    }

    @Test
    void testParseInfoWithInvalidTimestamp() {
        Map<String, String> map = Map.of(
                "content", "test-content",
                "updateTime", "invalid-timestamp",
                "createTime", "also-invalid"
        );

        ConfigInfo info = ConfigEntryCodec.parseInfo("test-data-id", "test-group", map);

        assertEquals("test-content", info.getContent());
        assertNull(info.getCreateTime());
        assertNull(info.getUpdateTime());
    }

    @Test
    void testParseInfoWithNullTimestamp() {
        // Use HashMap since Map.of() doesn't allow null values
        Map<String, String> map = new java.util.HashMap<>();
        map.put("content", "test-content");
        map.put("updateTime", null);
        map.put("createTime", null);

        ConfigInfo info = ConfigEntryCodec.parseInfo("test-data-id", "test-group", map);

        assertEquals("test-content", info.getContent());
        assertNull(info.getCreateTime());
        assertNull(info.getUpdateTime());
    }

    @Test
    void testParseInfoWithMissingTimestamp() {
        Map<String, String> map = Map.of(
                "content", "test-content"
        );

        ConfigInfo info = ConfigEntryCodec.parseInfo("test-data-id", "test-group", map);

        assertEquals("test-content", info.getContent());
        assertNull(info.getCreateTime());
        assertNull(info.getUpdateTime());
    }

    @Test
    void testPrivateConstructor() throws Exception {
        // Test that the private constructor exists
        java.lang.reflect.Constructor<ConfigEntryCodec> constructor =
                ConfigEntryCodec.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
    }

    @Test
    void testToMapAndParseInfoRoundTrip() {
        long createTime = System.currentTimeMillis() - 10000;
        long updateTime = System.currentTimeMillis();

        Map<String, String> map = ConfigEntryCodec.toMap(
                "test-content", "1.0.0", "test-description", updateTime, createTime
        );

        ConfigInfo info = ConfigEntryCodec.parseInfo("test-data-id", "test-group", map);

        assertEquals("test-content", info.getContent());
        assertEquals("1.0.0", info.getVersion());
        assertEquals("test-description", info.getDescription());
    }

    @Test
    void testParseInfoWithZeroTimestamp() {
        Map<String, String> map = Map.of(
                "content", "test-content",
                "updateTime", "0",
                "createTime", "0"
        );

        ConfigInfo info = ConfigEntryCodec.parseInfo("test-data-id", "test-group", map);

        assertEquals("test-content", info.getContent());
        assertNotNull(info.getCreateTime());
        assertNotNull(info.getUpdateTime());
    }

    @Test
    void testParseInfoWithNegativeTimestamp() {
        Map<String, String> map = Map.of(
                "content", "test-content",
                "updateTime", "-1000"
        );

        ConfigInfo info = ConfigEntryCodec.parseInfo("test-data-id", "test-group", map);

        assertEquals("test-content", info.getContent());
        assertNotNull(info.getUpdateTime());
    }

    @Test
    void testToMapReturnsModifiableMap() {
        Map<String, String> map = ConfigEntryCodec.toMap(
                "test-content", "1.0.0", "test-description", System.currentTimeMillis(), null
        );

        assertDoesNotThrow(() -> map.put("extra-key", "extra-value"));
        assertEquals("extra-value", map.get("extra-key"));
    }

    @Test
    void testToMapMinimal() {
        long updateTime = System.currentTimeMillis();

        Map<String, String> map = ConfigEntryCodec.toMap(
                null, null, null, updateTime, null
        );

        assertEquals(1, map.size());
        assertEquals(String.valueOf(updateTime), map.get("updateTime"));
    }
}
