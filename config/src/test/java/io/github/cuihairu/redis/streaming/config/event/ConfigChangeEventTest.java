package io.github.cuihairu.redis.streaming.config.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigChangeEvent
 */
class ConfigChangeEventTest {

    @Test
    void testNoArgsConstructor() {
        ConfigChangeEvent event = new ConfigChangeEvent();

        assertNull(event.getDataId());
        assertNull(event.getGroup());
        assertNull(event.getContent());
        assertNull(event.getVersion());
        assertEquals(0, event.getTimestamp());
    }

    @Test
    void testParameterizedConstructor() {
        ConfigChangeEvent event = new ConfigChangeEvent("data1", "group1", "content1", "v1.0", 123456789L);

        assertEquals("data1", event.getDataId());
        assertEquals("group1", event.getGroup());
        assertEquals("content1", event.getContent());
        assertEquals("v1.0", event.getVersion());
        assertEquals(123456789L, event.getTimestamp());
    }

    @Test
    void testSettersAndGetters() {
        ConfigChangeEvent event = new ConfigChangeEvent();

        event.setDataId("test-data");
        event.setGroup("test-group");
        event.setContent("test-content");
        event.setVersion("v2.0");
        event.setTimestamp(987654321L);

        assertEquals("test-data", event.getDataId());
        assertEquals("test-group", event.getGroup());
        assertEquals("test-content", event.getContent());
        assertEquals("v2.0", event.getVersion());
        assertEquals(987654321L, event.getTimestamp());
    }

    @Test
    void testWithNullValues() {
        ConfigChangeEvent event = new ConfigChangeEvent(null, null, null, null, 0L);

        assertNull(event.getDataId());
        assertNull(event.getGroup());
        assertNull(event.getContent());
        assertNull(event.getVersion());
        assertEquals(0, event.getTimestamp());
    }

    @Test
    void testWithEmptyStrings() {
        ConfigChangeEvent event = new ConfigChangeEvent("", "", "", "", 0L);

        assertEquals("", event.getDataId());
        assertEquals("", event.getGroup());
        assertEquals("", event.getContent());
        assertEquals("", event.getVersion());
        assertEquals(0, event.getTimestamp());
    }

    @Test
    void testWithNegativeTimestamp() {
        ConfigChangeEvent event = new ConfigChangeEvent("data1", "group1", "content1", "v1.0", -1000L);

        assertEquals(-1000L, event.getTimestamp());
    }

    @Test
    void testSettersWithNull() {
        ConfigChangeEvent event = new ConfigChangeEvent("data1", "group1", "content1", "v1.0", 123L);

        event.setDataId(null);
        event.setGroup(null);
        event.setContent(null);
        event.setVersion(null);

        assertNull(event.getDataId());
        assertNull(event.getGroup());
        assertNull(event.getContent());
        assertNull(event.getVersion());
        assertEquals(123L, event.getTimestamp());
    }

    @Test
    void testLongValueTimestamp() {
        long maxLong = Long.MAX_VALUE;
        ConfigChangeEvent event = new ConfigChangeEvent("data1", "group1", "content1", "v1.0", maxLong);

        assertEquals(maxLong, event.getTimestamp());
    }

    @Test
    void testMultipleEventInstances() {
        ConfigChangeEvent event1 = new ConfigChangeEvent("data1", "group1", "content1", "v1.0", 100L);
        ConfigChangeEvent event2 = new ConfigChangeEvent("data2", "group2", "content2", "v2.0", 200L);

        assertEquals("data1", event1.getDataId());
        assertEquals("data2", event2.getDataId());

        assertEquals(100L, event1.getTimestamp());
        assertEquals(200L, event2.getTimestamp());
    }
}
