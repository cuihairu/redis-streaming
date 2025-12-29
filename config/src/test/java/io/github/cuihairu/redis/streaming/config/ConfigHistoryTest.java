package io.github.cuihairu.redis.streaming.config;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigHistory
 */
class ConfigHistoryTest {

    @Test
    void testNoArgsConstructor() {
        ConfigHistory history = new ConfigHistory();

        assertNull(history.getDataId());
        assertNull(history.getGroup());
        assertNull(history.getContent());
        assertNull(history.getVersion());
        assertNull(history.getDescription());
        assertNull(history.getChangeTime());
        assertNull(history.getOperator());
    }

    @Test
    void testAllArgsConstructor() {
        LocalDateTime changeTime = LocalDateTime.now();
        ConfigHistory history = new ConfigHistory("data1", "group1", "content1", "v1.0", "update", changeTime, "admin");

        assertEquals("data1", history.getDataId());
        assertEquals("group1", history.getGroup());
        assertEquals("content1", history.getContent());
        assertEquals("v1.0", history.getVersion());
        assertEquals("update", history.getDescription());
        assertEquals(changeTime, history.getChangeTime());
        assertEquals("admin", history.getOperator());
    }

    @Test
    void testBuilder() {
        LocalDateTime changeTime = LocalDateTime.of(2024, 6, 15, 14, 30);

        ConfigHistory history = ConfigHistory.builder()
                .dataId("test-data")
                .group("test-group")
                .content("test-content")
                .version("v2.0")
                .description("initial commit")
                .changeTime(changeTime)
                .operator("user1")
                .build();

        assertEquals("test-data", history.getDataId());
        assertEquals("test-group", history.getGroup());
        assertEquals("test-content", history.getContent());
        assertEquals("v2.0", history.getVersion());
        assertEquals("initial commit", history.getDescription());
        assertEquals(changeTime, history.getChangeTime());
        assertEquals("user1", history.getOperator());
    }

    @Test
    void testSettersAndGetters() {
        ConfigHistory history = new ConfigHistory();
        LocalDateTime now = LocalDateTime.now();

        history.setDataId("new-data");
        history.setGroup("new-group");
        history.setContent("new-content");
        history.setVersion("v3.0");
        history.setDescription("new description");
        history.setChangeTime(now);
        history.setOperator("new-user");

        assertEquals("new-data", history.getDataId());
        assertEquals("new-group", history.getGroup());
        assertEquals("new-content", history.getContent());
        assertEquals("v3.0", history.getVersion());
        assertEquals("new description", history.getDescription());
        assertEquals(now, history.getChangeTime());
        assertEquals("new-user", history.getOperator());
    }

    @Test
    void testWithNullValues() {
        ConfigHistory history = ConfigHistory.builder()
                .dataId(null)
                .group(null)
                .content(null)
                .version(null)
                .description(null)
                .changeTime(null)
                .operator(null)
                .build();

        assertNull(history.getDataId());
        assertNull(history.getGroup());
        assertNull(history.getContent());
        assertNull(history.getVersion());
        assertNull(history.getDescription());
        assertNull(history.getChangeTime());
        assertNull(history.getOperator());
    }

    @Test
    void testWithEmptyStrings() {
        ConfigHistory history = ConfigHistory.builder()
                .dataId("")
                .group("")
                .content("")
                .version("")
                .description("")
                .operator("")
                .build();

        assertEquals("", history.getDataId());
        assertEquals("", history.getGroup());
        assertEquals("", history.getContent());
        assertEquals("", history.getVersion());
        assertEquals("", history.getDescription());
        assertEquals("", history.getOperator());
    }

    @Test
    void testLombokDataAnnotation() {
        LocalDateTime time = LocalDateTime.now();
        ConfigHistory history1 = new ConfigHistory("data1", "group1", "content1", "v1.0", "desc", time, "admin");
        ConfigHistory history2 = new ConfigHistory("data1", "group1", "content1", "v1.0", "desc", time, "admin");

        assertEquals(history1, history2);
        assertEquals(history1.hashCode(), history2.hashCode());
    }

    @Test
    void testBuilderWithPartialFields() {
        ConfigHistory history = ConfigHistory.builder()
                .dataId("data1")
                .version("v1.0")
                .build();

        assertEquals("data1", history.getDataId());
        assertEquals("v1.0", history.getVersion());
        assertNull(history.getGroup());
        assertNull(history.getContent());
        assertNull(history.getDescription());
        assertNull(history.getChangeTime());
        assertNull(history.getOperator());
    }

    @Test
    void testMultipleHistoryEntries() {
        LocalDateTime time1 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime time2 = LocalDateTime.of(2024, 1, 2, 11, 0);
        LocalDateTime time3 = LocalDateTime.of(2024, 1, 3, 12, 0);

        ConfigHistory h1 = ConfigHistory.builder()
                .dataId("app-config")
                .version("v1.0")
                .changeTime(time1)
                .operator("admin")
                .build();

        ConfigHistory h2 = ConfigHistory.builder()
                .dataId("app-config")
                .version("v1.1")
                .changeTime(time2)
                .operator("admin")
                .build();

        ConfigHistory h3 = ConfigHistory.builder()
                .dataId("app-config")
                .version("v1.2")
                .changeTime(time3)
                .operator("user1")
                .build();

        assertEquals("v1.0", h1.getVersion());
        assertEquals("v1.1", h2.getVersion());
        assertEquals("v1.2", h3.getVersion());
        assertEquals(time1, h1.getChangeTime());
        assertEquals(time2, h2.getChangeTime());
        assertEquals(time3, h3.getChangeTime());
    }

    @Test
    void testOperatorFormats() {
        ConfigHistory withUsername = ConfigHistory.builder().operator("john.doe").build();
        ConfigHistory withEmail = ConfigHistory.builder().operator("john@example.com").build();
        ConfigHistory withSystem = ConfigHistory.builder().operator("system").build();

        assertEquals("john.doe", withUsername.getOperator());
        assertEquals("john@example.com", withEmail.getOperator());
        assertEquals("system", withSystem.getOperator());
    }

    @Test
    void testDescriptionFormats() {
        ConfigHistory h1 = ConfigHistory.builder().description("Initial commit").build();
        ConfigHistory h2 = ConfigHistory.builder().description("Updated config value").build();
        ConfigHistory h3 = ConfigHistory.builder().description("").build();
        ConfigHistory h4 = ConfigHistory.builder().description("Fix typo in configuration").build();

        assertEquals("Initial commit", h1.getDescription());
        assertEquals("Updated config value", h2.getDescription());
        assertEquals("", h3.getDescription());
        assertEquals("Fix typo in configuration", h4.getDescription());
    }

    @Test
    void testVersionSequence() {
        String[] versions = {"v1.0.0", "v1.0.1", "v1.1.0", "v2.0.0"};

        for (String version : versions) {
            ConfigHistory history = ConfigHistory.builder()
                    .dataId("app-config")
                    .version(version)
                    .build();
            assertEquals(version, history.getVersion());
        }
    }

    @Test
    void testDifferentTimestamps() {
        LocalDateTime past = LocalDateTime.of(2020, 1, 1, 0, 0);
        LocalDateTime future = LocalDateTime.of(2030, 12, 31, 23, 59);
        LocalDateTime now = LocalDateTime.now();

        ConfigHistory h1 = ConfigHistory.builder().changeTime(past).build();
        ConfigHistory h2 = ConfigHistory.builder().changeTime(now).build();
        ConfigHistory h3 = ConfigHistory.builder().changeTime(future).build();

        assertEquals(past, h1.getChangeTime());
        assertEquals(now, h2.getChangeTime());
        assertEquals(future, h3.getChangeTime());
    }
}
