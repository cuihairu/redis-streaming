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
        ConfigInfo info = new ConfigInfo();

        assertNull(info.getDataId());
        assertNull(info.getGroup());
        assertNull(info.getContent());
        assertNull(info.getVersion());
        assertNull(info.getDescription());
        assertNull(info.getUpdateTime());
        assertNull(info.getCreateTime());
    }

    @Test
    void testAllArgsConstructor() {
        LocalDateTime now = LocalDateTime.now();
        ConfigInfo info = new ConfigInfo("data1", "group1", "content1", "v1.0", "desc1", now, now);

        assertEquals("data1", info.getDataId());
        assertEquals("group1", info.getGroup());
        assertEquals("content1", info.getContent());
        assertEquals("v1.0", info.getVersion());
        assertEquals("desc1", info.getDescription());
        assertEquals(now, info.getUpdateTime());
        assertEquals(now, info.getCreateTime());
    }

    @Test
    void testBuilder() {
        LocalDateTime createTime = LocalDateTime.of(2024, 1, 1, 10, 30);
        LocalDateTime updateTime = LocalDateTime.of(2024, 1, 2, 11, 45);

        ConfigInfo info = ConfigInfo.builder()
                .dataId("test-data")
                .group("test-group")
                .content("test-content")
                .version("v2.0")
                .description("test description")
                .createTime(createTime)
                .updateTime(updateTime)
                .build();

        assertEquals("test-data", info.getDataId());
        assertEquals("test-group", info.getGroup());
        assertEquals("test-content", info.getContent());
        assertEquals("v2.0", info.getVersion());
        assertEquals("test description", info.getDescription());
        assertEquals(createTime, info.getCreateTime());
        assertEquals(updateTime, info.getUpdateTime());
    }

    @Test
    void testSettersAndGetters() {
        ConfigInfo info = new ConfigInfo();
        LocalDateTime now = LocalDateTime.now();

        info.setDataId("new-data");
        info.setGroup("new-group");
        info.setContent("new-content");
        info.setVersion("v3.0");
        info.setDescription("new description");
        info.setCreateTime(now);
        info.setUpdateTime(now);

        assertEquals("new-data", info.getDataId());
        assertEquals("new-group", info.getGroup());
        assertEquals("new-content", info.getContent());
        assertEquals("v3.0", info.getVersion());
        assertEquals("new description", info.getDescription());
        assertEquals(now, info.getCreateTime());
        assertEquals(now, info.getUpdateTime());
    }

    @Test
    void testWithNullValues() {
        ConfigInfo info = ConfigInfo.builder()
                .dataId(null)
                .group(null)
                .content(null)
                .version(null)
                .description(null)
                .createTime(null)
                .updateTime(null)
                .build();

        assertNull(info.getDataId());
        assertNull(info.getGroup());
        assertNull(info.getContent());
        assertNull(info.getVersion());
        assertNull(info.getDescription());
        assertNull(info.getCreateTime());
        assertNull(info.getUpdateTime());
    }

    @Test
    void testWithEmptyStrings() {
        ConfigInfo info = ConfigInfo.builder()
                .dataId("")
                .group("")
                .content("")
                .version("")
                .description("")
                .build();

        assertEquals("", info.getDataId());
        assertEquals("", info.getGroup());
        assertEquals("", info.getContent());
        assertEquals("", info.getVersion());
        assertEquals("", info.getDescription());
    }

    @Test
    void testLombokDataAnnotation() {
        ConfigInfo info1 = new ConfigInfo("data1", "group1", "content1", "v1.0", "desc1", null, null);
        ConfigInfo info2 = new ConfigInfo("data1", "group1", "content1", "v1.0", "desc1", null, null);

        // Lombok @Data generates equals() and hashCode()
        assertEquals(info1, info2);
        assertEquals(info1.hashCode(), info2.hashCode());
    }

    @Test
    void testToString() {
        ConfigInfo info = ConfigInfo.builder()
                .dataId("test-data")
                .group("test-group")
                .version("v1.0")
                .build();

        String str = info.toString();
        assertTrue(str.contains("test-data") || str.contains("test-group") || str.contains("v1.0"));
    }

    @Test
    void testBuilderWithPartialFields() {
        ConfigInfo info = ConfigInfo.builder()
                .dataId("data1")
                .group("group1")
                .build();

        assertEquals("data1", info.getDataId());
        assertEquals("group1", info.getGroup());
        assertNull(info.getContent());
        assertNull(info.getVersion());
        assertNull(info.getDescription());
        assertNull(info.getCreateTime());
        assertNull(info.getUpdateTime());
    }

    @Test
    void testMultipleConfigInfoInstances() {
        LocalDateTime time1 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime time2 = LocalDateTime.of(2024, 2, 1, 11, 0);

        ConfigInfo info1 = ConfigInfo.builder()
                .dataId("data1")
                .group("group1")
                .createTime(time1)
                .updateTime(time1)
                .build();

        ConfigInfo info2 = ConfigInfo.builder()
                .dataId("data2")
                .group("group2")
                .createTime(time2)
                .updateTime(time2)
                .build();

        assertEquals("data1", info1.getDataId());
        assertEquals("data2", info2.getDataId());
        assertEquals(time1, info1.getCreateTime());
        assertEquals(time2, info2.getCreateTime());
    }

    @Test
    void testCanModifyImmutableFields() {
        ConfigInfo info = ConfigInfo.builder()
                .dataId("original")
                .content("original content")
                .build();

        // Lombok @Data generates mutable setters
        info.setDataId("modified");
        info.setContent("modified content");

        assertEquals("modified", info.getDataId());
        assertEquals("modified content", info.getContent());
    }

    @Test
    void testWithSpecialCharactersInContent() {
        String specialContent = "{\n  \"key\": \"value\",\n  \"array\": [1, 2, 3]\n}";
        ConfigInfo info = ConfigInfo.builder()
                .dataId("json-config")
                .content(specialContent)
                .build();

        assertEquals(specialContent, info.getContent());
    }

    @Test
    void testVersionFormats() {
        ConfigInfo semanticVersion = ConfigInfo.builder().version("1.2.3").build();
        ConfigInfo timestampVersion = ConfigInfo.builder().version("20240101120000").build();
        ConfigInfo hashVersion = ConfigInfo.builder().version("abc123def").build();

        assertEquals("1.2.3", semanticVersion.getVersion());
        assertEquals("20240101120000", timestampVersion.getVersion());
        assertEquals("abc123def", hashVersion.getVersion());
    }
}
