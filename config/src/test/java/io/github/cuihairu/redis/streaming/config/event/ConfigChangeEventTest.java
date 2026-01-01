package io.github.cuihairu.redis.streaming.config.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigChangeEvent
 */
class ConfigChangeEventTest {

    @Test
    void testDefaultConstructor() {
        // Given & When
        ConfigChangeEvent event = new ConfigChangeEvent();

        // Then
        assertNotNull(event);
        assertNull(event.getDataId());
        assertNull(event.getGroup());
        assertNull(event.getContent());
        assertNull(event.getVersion());
        assertEquals(0, event.getTimestamp());
    }

    @Test
    void testParameterizedConstructor() {
        // Given
        String dataId = "test-data-id";
        String group = "test-group";
        String content = "test content";
        String version = "1.0.0";
        long timestamp = System.currentTimeMillis();

        // When
        ConfigChangeEvent event = new ConfigChangeEvent(dataId, group, content, version, timestamp);

        // Then
        assertEquals(dataId, event.getDataId());
        assertEquals(group, event.getGroup());
        assertEquals(content, event.getContent());
        assertEquals(version, event.getVersion());
        assertEquals(timestamp, event.getTimestamp());
    }

    @Test
    void testSetDataId() {
        // Given
        ConfigChangeEvent event = new ConfigChangeEvent();
        String dataId = "new-data-id";

        // When
        event.setDataId(dataId);

        // Then
        assertEquals(dataId, event.getDataId());
    }

    @Test
    void testSetGroup() {
        // Given
        ConfigChangeEvent event = new ConfigChangeEvent();
        String group = "new-group";

        // When
        event.setGroup(group);

        // Then
        assertEquals(group, event.getGroup());
    }

    @Test
    void testSetContent() {
        // Given
        ConfigChangeEvent event = new ConfigChangeEvent();
        String content = "new content";

        // When
        event.setContent(content);

        // Then
        assertEquals(content, event.getContent());
    }

    @Test
    void testSetVersion() {
        // Given
        ConfigChangeEvent event = new ConfigChangeEvent();
        String version = "2.0.0";

        // When
        event.setVersion(version);

        // Then
        assertEquals(version, event.getVersion());
    }

    @Test
    void testSetTimestamp() {
        // Given
        ConfigChangeEvent event = new ConfigChangeEvent();
        long timestamp = 1234567890L;

        // When
        event.setTimestamp(timestamp);

        // Then
        assertEquals(timestamp, event.getTimestamp());
    }

    @Test
    void testGetDataId() {
        // Given
        String dataId = "test-data-id";
        ConfigChangeEvent event = new ConfigChangeEvent(dataId, "group", "content", "version", 0L);

        // When
        String result = event.getDataId();

        // Then
        assertEquals(dataId, result);
    }

    @Test
    void testGetGroup() {
        // Given
        String group = "test-group";
        ConfigChangeEvent event = new ConfigChangeEvent("dataId", group, "content", "version", 0L);

        // When
        String result = event.getGroup();

        // Then
        assertEquals(group, result);
    }

    @Test
    void testGetContent() {
        // Given
        String content = "test content";
        ConfigChangeEvent event = new ConfigChangeEvent("dataId", "group", content, "version", 0L);

        // When
        String result = event.getContent();

        // Then
        assertEquals(content, result);
    }

    @Test
    void testGetVersion() {
        // Given
        String version = "1.0.0";
        ConfigChangeEvent event = new ConfigChangeEvent("dataId", "group", "content", version, 0L);

        // When
        String result = event.getVersion();

        // Then
        assertEquals(version, result);
    }

    @Test
    void testGetTimestamp() {
        // Given
        long timestamp = 9876543210L;
        ConfigChangeEvent event = new ConfigChangeEvent("dataId", "group", "content", "version", timestamp);

        // When
        long result = event.getTimestamp();

        // Then
        assertEquals(timestamp, result);
    }

    @Test
    void testAllSettersAndGetters() {
        // Given
        ConfigChangeEvent event = new ConfigChangeEvent();
        String dataId = "data-id";
        String group = "group";
        String content = "content";
        String version = "version";
        long timestamp = System.currentTimeMillis();

        // When
        event.setDataId(dataId);
        event.setGroup(group);
        event.setContent(content);
        event.setVersion(version);
        event.setTimestamp(timestamp);

        // Then
        assertEquals(dataId, event.getDataId());
        assertEquals(group, event.getGroup());
        assertEquals(content, event.getContent());
        assertEquals(version, event.getVersion());
        assertEquals(timestamp, event.getTimestamp());
    }

    @Test
    void testConstructorWithEmptyStrings() {
        // Given & When
        ConfigChangeEvent event = new ConfigChangeEvent("", "", "", "", 0L);

        // Then
        assertEquals("", event.getDataId());
        assertEquals("", event.getGroup());
        assertEquals("", event.getContent());
        assertEquals("", event.getVersion());
        assertEquals(0L, event.getTimestamp());
    }

    @Test
    void testConstructorWithNullValues() {
        // Given & When
        ConfigChangeEvent event = new ConfigChangeEvent(null, null, null, null, 0L);

        // Then
        assertNull(event.getDataId());
        assertNull(event.getGroup());
        assertNull(event.getContent());
        assertNull(event.getVersion());
        assertEquals(0L, event.getTimestamp());
    }

    @Test
    void testMultipleEvents() {
        // Given
        ConfigChangeEvent event1 = new ConfigChangeEvent("id1", "group1", "content1", "v1", 1000L);
        ConfigChangeEvent event2 = new ConfigChangeEvent("id2", "group2", "content2", "v2", 2000L);

        // When & Then
        assertNotEquals(event1.getDataId(), event2.getDataId());
        assertNotEquals(event1.getTimestamp(), event2.getTimestamp());
    }

    @Test
    void testEventWithCurrentTimestamp() {
        // Given
        long before = System.currentTimeMillis();
        ConfigChangeEvent event = new ConfigChangeEvent("id", "group", "content", "version", before);
        long after = System.currentTimeMillis();

        // When & Then
        assertTrue(event.getTimestamp() >= before);
        assertTrue(event.getTimestamp() <= after);
    }
}
