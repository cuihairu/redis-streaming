package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.impl.ConfigEntryCodec;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigEntryCodecTest {

    @Test
    void testParseInfo() {
        long now = System.currentTimeMillis();
        Map<String,String> map = new java.util.HashMap<>();
        map.put("content", "v1");
        map.put("version", "ts-1");
        map.put("description", "init");
        map.put("updateTime", String.valueOf(now));
        map.put("createTime", String.valueOf(now - 1000));

        ConfigInfo info = ConfigEntryCodec.parseInfo("d","g", map);
        assertEquals("v1", info.getContent());
        assertEquals("ts-1", info.getVersion());
        assertEquals("init", info.getDescription());
        assertNotNull(info.getUpdateTime());
        assertNotNull(info.getCreateTime());
    }

    @Test
    void parseInfo_withNullMap() {
        ConfigInfo info = ConfigEntryCodec.parseInfo("d", "g", null);
        assertNull(info);
    }

    @Test
    void parseInfo_withEmptyMap() {
        Map<String, String> map = new java.util.HashMap<>();
        ConfigInfo info = ConfigEntryCodec.parseInfo("d", "g", map);

        assertNotNull(info);
        assertEquals("d", info.getDataId());
        assertEquals("g", info.getGroup());
        assertNull(info.getContent());
        assertNull(info.getVersion());
        assertNull(info.getDescription());
        assertNull(info.getUpdateTime());
        assertNull(info.getCreateTime());
    }

    @Test
    void parseInfo_withPartialFields() {
        Map<String, String> map = new java.util.HashMap<>();
        map.put("content", "my-content");

        ConfigInfo info = ConfigEntryCodec.parseInfo("data1", "group1", map);

        assertEquals("data1", info.getDataId());
        assertEquals("group1", info.getGroup());
        assertEquals("my-content", info.getContent());
        assertNull(info.getVersion());
        assertNull(info.getDescription());
    }

    @Test
    void parseInfo_withInvalidTimestamp() {
        Map<String, String> map = new java.util.HashMap<>();
        map.put("content", "v1");
        map.put("updateTime", "invalid-timestamp");
        map.put("createTime", "also-invalid");

        ConfigInfo info = ConfigEntryCodec.parseInfo("d", "g", map);

        assertEquals("v1", info.getContent());
        assertNull(info.getUpdateTime());
        assertNull(info.getCreateTime());
    }

    @Test
    void parseInfo_withNullTimestamp() {
        Map<String, String> map = new java.util.HashMap<>();
        map.put("content", "v1");
        map.put("updateTime", null);
        map.put("createTime", null);

        ConfigInfo info = ConfigEntryCodec.parseInfo("d", "g", map);

        assertEquals("v1", info.getContent());
        assertNull(info.getUpdateTime());
        assertNull(info.getCreateTime());
    }

    @Test
    void parseInfo_preservesDataIdAndGroup() {
        Map<String, String> map = new java.util.HashMap<>();
        map.put("content", "content-value");

        ConfigInfo info = ConfigEntryCodec.parseInfo("my-data-id", "my-group", map);

        assertEquals("my-data-id", info.getDataId());
        assertEquals("my-group", info.getGroup());
    }

    @Test
    void toMap_withAllFields() {
        long now = System.currentTimeMillis();
        Map<String, String> result = ConfigEntryCodec.toMap("content", "v1", "desc", now, now - 1000);

        assertEquals("content", result.get("content"));
        assertEquals("v1", result.get("version"));
        assertEquals("desc", result.get("description"));
        assertEquals(String.valueOf(now), result.get("updateTime"));
        assertEquals(String.valueOf(now - 1000), result.get("createTime"));
    }

    @Test
    void toMap_withNullContent() {
        long now = System.currentTimeMillis();
        Map<String, String> result = ConfigEntryCodec.toMap(null, "v1", "desc", now, null);

        assertNull(result.get("content"));
        assertEquals("v1", result.get("version"));
        assertEquals("desc", result.get("description"));
        assertEquals(String.valueOf(now), result.get("updateTime"));
        assertNull(result.get("createTime"));
    }

    @Test
    void toMap_withNullVersion() {
        long now = System.currentTimeMillis();
        Map<String, String> result = ConfigEntryCodec.toMap("content", null, "desc", now, now - 1000);

        assertEquals("content", result.get("content"));
        assertNull(result.get("version"));
        assertEquals("desc", result.get("description"));
    }

    @Test
    void toMap_withEmptyDescription() {
        long now = System.currentTimeMillis();
        Map<String, String> result = ConfigEntryCodec.toMap("content", "v1", "", now, null);

        assertEquals("content", result.get("content"));
        assertEquals("v1", result.get("version"));
        assertNull(result.get("description")); // empty description is not included
        assertEquals(String.valueOf(now), result.get("updateTime"));
        assertNull(result.get("createTime"));
    }

    @Test
    void toMap_withNullDescription() {
        long now = System.currentTimeMillis();
        Map<String, String> result = ConfigEntryCodec.toMap("content", "v1", null, now, null);

        assertEquals("content", result.get("content"));
        assertEquals("v1", result.get("version"));
        assertNull(result.get("description"));
    }

    @Test
    void toMap_returnsMutableMap() {
        long now = System.currentTimeMillis();
        Map<String, String> result = ConfigEntryCodec.toMap("content", "v1", "desc", now, null);

        // Should be able to modify the returned map
        result.put("extra", "value");
        assertEquals("value", result.get("extra"));
    }

    @Test
    void parseInfo_withOnlyContent() {
        Map<String, String> map = new java.util.HashMap<>();
        map.put("content", "only-content");

        ConfigInfo info = ConfigEntryCodec.parseInfo("d", "g", map);

        assertEquals("only-content", info.getContent());
        assertNull(info.getVersion());
        assertNull(info.getDescription());
    }

    @Test
    void parseInfo_withOnlyVersion() {
        Map<String, String> map = new java.util.HashMap<>();
        map.put("version", "version-123");

        ConfigInfo info = ConfigEntryCodec.parseInfo("d", "g", map);

        assertNull(info.getContent());
        assertEquals("version-123", info.getVersion());
        assertNull(info.getDescription());
    }

    @Test
    void parseInfo_withOnlyDescription() {
        Map<String, String> map = new java.util.HashMap<>();
        map.put("description", "my description");

        ConfigInfo info = ConfigEntryCodec.parseInfo("d", "g", map);

        assertNull(info.getContent());
        assertNull(info.getVersion());
        assertEquals("my description", info.getDescription());
    }

    @Test
    void parseInfo_withNegativeTimestamp() {
        Map<String, String> map = new java.util.HashMap<>();
        map.put("content", "v1");
        map.put("updateTime", "-1000");
        map.put("createTime", "-5000");

        ConfigInfo info = ConfigEntryCodec.parseInfo("d", "g", map);

        assertEquals("v1", info.getContent());
        assertNotNull(info.getUpdateTime());
        assertNotNull(info.getCreateTime());
    }

    @Test
    void parseInfo_withVeryLargeTimestamp() {
        Map<String, String> map = new java.util.HashMap<>();
        map.put("content", "v1");
        map.put("updateTime", "9999999999999");

        ConfigInfo info = ConfigEntryCodec.parseInfo("d", "g", map);

        assertEquals("v1", info.getContent());
        assertNotNull(info.getUpdateTime());
    }

    @Test
    void parseInfo_withZeroTimestamp() {
        Map<String, String> map = new java.util.HashMap<>();
        map.put("updateTime", "0");
        map.put("createTime", "0");

        ConfigInfo info = ConfigEntryCodec.parseInfo("d", "g", map);

        assertNotNull(info.getUpdateTime());
        assertNotNull(info.getCreateTime());
    }

    @Test
    void toMap_withMinimalFields() {
        long now = System.currentTimeMillis();
        Map<String, String> result = ConfigEntryCodec.toMap(null, null, null, now, null);

        assertNull(result.get("content"));
        assertNull(result.get("version"));
        assertNull(result.get("description"));
        assertEquals(String.valueOf(now), result.get("updateTime"));
        assertNull(result.get("createTime"));
    }
}
