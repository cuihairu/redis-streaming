package io.github.cuihairu.redis.streaming.config;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigInfo
 */
class ConfigInfoTest {

    @Test
    void testNoArgsConstructor() {
        // Given & When
        ConfigInfo configInfo = new ConfigInfo();

        // Then
        assertNotNull(configInfo);
        assertNull(configInfo.getDataId());
        assertNull(configInfo.getGroup());
        assertNull(configInfo.getContent());
        assertNull(configInfo.getVersion());
        assertNull(configInfo.getDescription());
        assertNull(configInfo.getUpdateTime());
        assertNull(configInfo.getCreateTime());
    }

    @Test
    void testAllArgsConstructor() {
        // Given
        String dataId = "test-data-id";
        String group = "test-group";
        String content = "test content";
        String version = "1.0.0";
        String description = "test description";
        LocalDateTime updateTime = LocalDateTime.now();
        LocalDateTime createTime = LocalDateTime.now();

        // When
        ConfigInfo configInfo = new ConfigInfo(dataId, group, content, version, description, updateTime, createTime);

        // Then
        assertEquals(dataId, configInfo.getDataId());
        assertEquals(group, configInfo.getGroup());
        assertEquals(content, configInfo.getContent());
        assertEquals(version, configInfo.getVersion());
        assertEquals(description, configInfo.getDescription());
        assertEquals(updateTime, configInfo.getUpdateTime());
        assertEquals(createTime, configInfo.getCreateTime());
    }

    @Test
    void testBuilder() {
        // Given
        String dataId = "test-data-id";
        String group = "test-group";
        String content = "test content";
        String version = "1.0.0";

        // When
        ConfigInfo configInfo = ConfigInfo.builder()
                .dataId(dataId)
                .group(group)
                .content(content)
                .version(version)
                .build();

        // Then
        assertEquals(dataId, configInfo.getDataId());
        assertEquals(group, configInfo.getGroup());
        assertEquals(content, configInfo.getContent());
        assertEquals(version, configInfo.getVersion());
    }

    @Test
    void testBuilderWithAllFields() {
        // Given
        LocalDateTime now = LocalDateTime.now();

        // When
        ConfigInfo configInfo = ConfigInfo.builder()
                .dataId("data-id")
                .group("group")
                .content("content")
                .version("version")
                .description("description")
                .updateTime(now)
                .createTime(now)
                .build();

        // Then
        assertEquals("data-id", configInfo.getDataId());
        assertEquals("group", configInfo.getGroup());
        assertEquals("content", configInfo.getContent());
        assertEquals("version", configInfo.getVersion());
        assertEquals("description", configInfo.getDescription());
        assertEquals(now, configInfo.getUpdateTime());
        assertEquals(now, configInfo.getCreateTime());
    }

    @Test
    void testSetters() {
        // Given
        ConfigInfo configInfo = new ConfigInfo();
        String dataId = "new-data-id";

        // When
        configInfo.setDataId(dataId);

        // Then
        assertEquals(dataId, configInfo.getDataId());
    }

    @Test
    void testSettersAllFields() {
        // Given
        ConfigInfo configInfo = new ConfigInfo();
        LocalDateTime now = LocalDateTime.now();

        // When
        configInfo.setDataId("data-id");
        configInfo.setGroup("group");
        configInfo.setContent("content");
        configInfo.setVersion("version");
        configInfo.setDescription("description");
        configInfo.setUpdateTime(now);
        configInfo.setCreateTime(now);

        // Then
        assertEquals("data-id", configInfo.getDataId());
        assertEquals("group", configInfo.getGroup());
        assertEquals("content", configInfo.getContent());
        assertEquals("version", configInfo.getVersion());
        assertEquals("description", configInfo.getDescription());
        assertEquals(now, configInfo.getUpdateTime());
        assertEquals(now, configInfo.getCreateTime());
    }

    @Test
    void testGetters() {
        // Given
        String dataId = "test-data-id";
        ConfigInfo configInfo = new ConfigInfo(dataId, "group", "content", "version", 
            "description", LocalDateTime.now(), LocalDateTime.now());

        // When
        String result = configInfo.getDataId();

        // Then
        assertEquals(dataId, result);
    }

    @Test
    void testEquals() {
        // Given
        ConfigInfo config1 = ConfigInfo.builder()
                .dataId("id")
                .group("group")
                .version("1.0")
                .build();

        ConfigInfo config2 = ConfigInfo.builder()
                .dataId("id")
                .group("group")
                .version("1.0")
                .build();

        ConfigInfo config3 = ConfigInfo.builder()
                .dataId("id2")
                .group("group")
                .version("1.0")
                .build();

        // Then
        assertEquals(config1, config2);
        assertNotEquals(config1, config3);
    }

    @Test
    void testHashCode() {
        // Given
        ConfigInfo config1 = ConfigInfo.builder()
                .dataId("id")
                .group("group")
                .version("1.0")
                .build();

        ConfigInfo config2 = ConfigInfo.builder()
                .dataId("id")
                .group("group")
                .version("1.0")
                .build();

        // Then
        assertEquals(config1.hashCode(), config2.hashCode());
    }

    @Test
    void testToString() {
        // Given
        ConfigInfo configInfo = ConfigInfo.builder()
                .dataId("test-id")
                .group("test-group")
                .build();

        // When
        String str = configInfo.toString();

        // Then
        assertNotNull(str);
        assertTrue(str.contains("ConfigInfo"));
    }

    @Test
    void testCanEqual() {
        // Given
        ConfigInfo configInfo = new ConfigInfo();
        Object obj = new Object();

        // When & Then
        assertNotEquals(configInfo, obj);
        assertEquals(configInfo, configInfo);
    }
}
