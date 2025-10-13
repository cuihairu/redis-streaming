package io.github.cuihairu.redis.streaming.source.impl;

import io.github.cuihairu.redis.streaming.source.SourceConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSourceConfigurationTest {

    @Test
    void testBasicConfiguration() {
        DefaultSourceConfiguration config = new DefaultSourceConfiguration("test-connector", "http");

        assertEquals("test-connector", config.getName());
        assertEquals("http", config.getType());
        assertEquals(1000L, config.getPollingIntervalMs());
        assertEquals(100, config.getBatchSize());
        assertTrue(config.isAutoStart());
        assertNotNull(config.getProperties());
    }

    @Test
    void testBuilderPattern() {
        DefaultSourceConfiguration config = DefaultSourceConfiguration.builder()
                .name("test-connector")
                .type("file")
                .property("directory", "/tmp")
                .property("pattern", "*.txt")
                .pollingIntervalMs(5000)
                .batchSize(50)
                .autoStart(false)
                .build();

        assertEquals("test-connector", config.getName());
        assertEquals("file", config.getType());
        assertEquals("/tmp", config.getProperty("directory"));
        assertEquals("*.txt", config.getProperty("pattern"));
        assertEquals(5000L, config.getPollingIntervalMs());
        assertEquals(50, config.getBatchSize());
        assertFalse(config.isAutoStart());
    }

    @Test
    void testPropertyAccess() {
        DefaultSourceConfiguration config = DefaultSourceConfiguration.builder()
                .name("test")
                .type("test")
                .property("key1", "value1")
                .property("key2", 42)
                .build();

        assertEquals("value1", config.getProperty("key1"));
        assertEquals(42, config.getProperty("key2"));
        assertNull(config.getProperty("nonexistent"));
        assertEquals("default", config.getProperty("nonexistent", "default"));
    }

    @Test
    void testPropertiesMap() {
        Map<String, Object> props = Map.of(
                "url", "http://example.com",
                "timeout", 30000
        );

        DefaultSourceConfiguration config = DefaultSourceConfiguration.builder()
                .name("test")
                .type("http")
                .properties(props)
                .build();

        assertEquals("http://example.com", config.getProperty("url"));
        assertEquals(30000, config.getProperty("timeout"));
    }

    @Test
    void testValidation() {
        // Valid configuration should not throw
        DefaultSourceConfiguration validConfig = new DefaultSourceConfiguration(
                "test", "http", Map.of("url", "http://example.com"), 1000, 10, true);

        assertDoesNotThrow(validConfig::validate);
    }

    @Test
    void testValidationFailures() {
        // Null name
        DefaultSourceConfiguration config1 = new DefaultSourceConfiguration(
                null, "http", Map.of(), 1000, 10, true);
        assertThrows(IllegalArgumentException.class, config1::validate);

        // Empty name
        DefaultSourceConfiguration config2 = new DefaultSourceConfiguration(
                "", "http", Map.of(), 1000, 10, true);
        assertThrows(IllegalArgumentException.class, config2::validate);

        // Null type
        DefaultSourceConfiguration config3 = new DefaultSourceConfiguration(
                "test", null, Map.of(), 1000, 10, true);
        assertThrows(IllegalArgumentException.class, config3::validate);

        // Negative polling interval
        DefaultSourceConfiguration config4 = new DefaultSourceConfiguration(
                "test", "http", Map.of(), -1, 10, true);
        assertThrows(IllegalArgumentException.class, config4::validate);

        // Zero batch size
        DefaultSourceConfiguration config5 = new DefaultSourceConfiguration(
                "test", "http", Map.of(), 1000, 0, true);
        assertThrows(IllegalArgumentException.class, config5::validate);
    }

    @Test
    void testHttpValidation() {
        // Missing URL
        DefaultSourceConfiguration config1 = new DefaultSourceConfiguration(
                "test", "http", Map.of(), 1000, 10, true);
        assertThrows(IllegalArgumentException.class, config1::validate);

        // Invalid URL
        DefaultSourceConfiguration config2 = new DefaultSourceConfiguration(
                "test", "http", Map.of("url", "invalid-url"), 1000, 10, true);
        assertThrows(IllegalArgumentException.class, config2::validate);

        // Invalid method
        DefaultSourceConfiguration config3 = new DefaultSourceConfiguration(
                "test", "http", Map.of("url", "http://example.com", "method", "INVALID"), 1000, 10, true);
        assertThrows(IllegalArgumentException.class, config3::validate);

        // Valid HTTP config
        DefaultSourceConfiguration validConfig = new DefaultSourceConfiguration(
                "test", "http", Map.of("url", "https://example.com", "method", "GET"), 1000, 10, true);
        assertDoesNotThrow(validConfig::validate);
    }

    @Test
    void testFileValidation() {
        // Missing directory
        DefaultSourceConfiguration config1 = new DefaultSourceConfiguration(
                "test", "file", Map.of(), 1000, 10, true);
        assertThrows(IllegalArgumentException.class, config1::validate);

        // Invalid watch mode
        DefaultSourceConfiguration config2 = new DefaultSourceConfiguration(
                "test", "file", Map.of("directory", "/tmp", "watch.mode", "invalid"), 1000, 10, true);
        assertThrows(IllegalArgumentException.class, config2::validate);

        // Valid file config
        DefaultSourceConfiguration validConfig = new DefaultSourceConfiguration(
                "test", "file", Map.of("directory", "/tmp", "watch.mode", "create"), 1000, 10, true);
        assertDoesNotThrow(validConfig::validate);
    }

    @Test
    void testIoTValidation() {
        // Invalid device count
        DefaultSourceConfiguration config1 = new DefaultSourceConfiguration(
                "test", "iot", Map.of("device.count", "0"), 1000, 10, true);
        assertThrows(IllegalArgumentException.class, config1::validate);

        // Invalid data interval
        DefaultSourceConfiguration config2 = new DefaultSourceConfiguration(
                "test", "iot", Map.of("device.count", "5", "data.interval.ms", "-1"), 1000, 10, true);
        assertThrows(IllegalArgumentException.class, config2::validate);

        // Valid IoT config
        DefaultSourceConfiguration validConfig = new DefaultSourceConfiguration(
                "test", "iot", Map.of("device.count", "10", "data.interval.ms", "1000"), 1000, 10, true);
        assertDoesNotThrow(validConfig::validate);
    }
}