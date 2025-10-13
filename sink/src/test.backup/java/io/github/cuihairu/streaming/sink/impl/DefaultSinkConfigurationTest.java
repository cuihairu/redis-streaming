package io.github.cuihairu.redis.streaming.sink.impl;

import io.github.cuihairu.redis.streaming.sink.SinkConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSinkConfigurationTest {

    @Test
    void testBasicConfiguration() {
        DefaultSinkConfiguration config = new DefaultSinkConfiguration("test-connector", "file");

        assertEquals("test-connector", config.getName());
        assertEquals("file", config.getType());
        assertEquals(100, config.getBatchSize());
        assertEquals(5000L, config.getFlushIntervalMs());
        assertEquals(3, config.getMaxRetries());
        assertEquals(1000L, config.getRetryBackoffMs());
        assertTrue(config.isAutoStart());
        assertTrue(config.isAutoFlush());
        assertNotNull(config.getProperties());
    }

    @Test
    void testBuilderPattern() {
        DefaultSinkConfiguration config = DefaultSinkConfiguration.builder()
                .name("test-connector")
                .type("elasticsearch")
                .property("hosts", "localhost:9200")
                .property("index", "test-index")
                .batchSize(50)
                .flushIntervalMs(10000)
                .maxRetries(5)
                .retryBackoffMs(2000)
                .autoStart(false)
                .autoFlush(false)
                .build();

        assertEquals("test-connector", config.getName());
        assertEquals("elasticsearch", config.getType());
        assertEquals("localhost:9200", config.getProperty("hosts"));
        assertEquals("test-index", config.getProperty("index"));
        assertEquals(50, config.getBatchSize());
        assertEquals(10000L, config.getFlushIntervalMs());
        assertEquals(5, config.getMaxRetries());
        assertEquals(2000L, config.getRetryBackoffMs());
        assertFalse(config.isAutoStart());
        assertFalse(config.isAutoFlush());
    }

    @Test
    void testPropertyAccess() {
        DefaultSinkConfiguration config = DefaultSinkConfiguration.builder()
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
                "hosts", "localhost:9200",
                "timeout", 30000
        );

        DefaultSinkConfiguration config = DefaultSinkConfiguration.builder()
                .name("test")
                .type("elasticsearch")
                .properties(props)
                .build();

        assertEquals("localhost:9200", config.getProperty("hosts"));
        assertEquals(30000, config.getProperty("timeout"));
    }

    @Test
    void testValidation() {
        // Valid configuration should not throw
        DefaultSinkConfiguration validConfig = new DefaultSinkConfiguration(
                "test", "file", Map.of("directory", "/tmp"), 10, 1000, 3, 500, true, true);

        assertDoesNotThrow(validConfig::validate);
    }

    @Test
    void testValidationFailures() {
        // Null name
        DefaultSinkConfiguration config1 = new DefaultSinkConfiguration(
                null, "file", Map.of(), 10, 1000, 3, 500, true, true);
        assertThrows(IllegalArgumentException.class, config1::validate);

        // Empty name
        DefaultSinkConfiguration config2 = new DefaultSinkConfiguration(
                "", "file", Map.of(), 10, 1000, 3, 500, true, true);
        assertThrows(IllegalArgumentException.class, config2::validate);

        // Null type
        DefaultSinkConfiguration config3 = new DefaultSinkConfiguration(
                "test", null, Map.of(), 10, 1000, 3, 500, true, true);
        assertThrows(IllegalArgumentException.class, config3::validate);

        // Zero batch size
        DefaultSinkConfiguration config4 = new DefaultSinkConfiguration(
                "test", "file", Map.of(), 0, 1000, 3, 500, true, true);
        assertThrows(IllegalArgumentException.class, config4::validate);

        // Negative flush interval
        DefaultSinkConfiguration config5 = new DefaultSinkConfiguration(
                "test", "file", Map.of(), 10, -1, 3, 500, true, true);
        assertThrows(IllegalArgumentException.class, config5::validate);

        // Negative max retries
        DefaultSinkConfiguration config6 = new DefaultSinkConfiguration(
                "test", "file", Map.of(), 10, 1000, -1, 500, true, true);
        assertThrows(IllegalArgumentException.class, config6::validate);

        // Negative retry backoff
        DefaultSinkConfiguration config7 = new DefaultSinkConfiguration(
                "test", "file", Map.of(), 10, 1000, 3, -1, true, true);
        assertThrows(IllegalArgumentException.class, config7::validate);
    }

    @Test
    void testElasticsearchValidation() {
        // Missing hosts
        DefaultSinkConfiguration config1 = new DefaultSinkConfiguration(
                "test", "elasticsearch", Map.of(), 10, 1000, 3, 500, true, true);
        assertThrows(IllegalArgumentException.class, config1::validate);

        // Missing index and index.pattern
        DefaultSinkConfiguration config2 = new DefaultSinkConfiguration(
                "test", "elasticsearch", Map.of("hosts", "localhost:9200"), 10, 1000, 3, 500, true, true);
        assertThrows(IllegalArgumentException.class, config2::validate);

        // Valid Elasticsearch config
        DefaultSinkConfiguration validConfig = new DefaultSinkConfiguration(
                "test", "elasticsearch",
                Map.of("hosts", "localhost:9200", "index", "test-index"),
                10, 1000, 3, 500, true, true);
        assertDoesNotThrow(validConfig::validate);
    }

    @Test
    void testDatabaseValidation() {
        // Missing jdbc.url
        DefaultSinkConfiguration config1 = new DefaultSinkConfiguration(
                "test", "database", Map.of(), 10, 1000, 3, 500, true, true);
        assertThrows(IllegalArgumentException.class, config1::validate);

        // Missing username
        DefaultSinkConfiguration config2 = new DefaultSinkConfiguration(
                "test", "database", Map.of("jdbc.url", "jdbc:h2:mem:test"), 10, 1000, 3, 500, true, true);
        assertThrows(IllegalArgumentException.class, config2::validate);

        // Missing password
        DefaultSinkConfiguration config3 = new DefaultSinkConfiguration(
                "test", "database",
                Map.of("jdbc.url", "jdbc:h2:mem:test", "username", "sa"),
                10, 1000, 3, 500, true, true);
        assertThrows(IllegalArgumentException.class, config3::validate);

        // Valid database config
        DefaultSinkConfiguration validConfig = new DefaultSinkConfiguration(
                "test", "database",
                Map.of("jdbc.url", "jdbc:h2:mem:test", "username", "sa", "password", ""),
                10, 1000, 3, 500, true, true);
        assertDoesNotThrow(validConfig::validate);
    }

    @Test
    void testFileValidation() {
        // Missing directory
        DefaultSinkConfiguration config1 = new DefaultSinkConfiguration(
                "test", "file", Map.of(), 10, 1000, 3, 500, true, true);
        assertThrows(IllegalArgumentException.class, config1::validate);

        // Invalid format
        DefaultSinkConfiguration config2 = new DefaultSinkConfiguration(
                "test", "file", Map.of("directory", "/tmp", "format", "INVALID"), 10, 1000, 3, 500, true, true);
        assertThrows(IllegalArgumentException.class, config2::validate);

        // Invalid rotation size
        DefaultSinkConfiguration config3 = new DefaultSinkConfiguration(
                "test", "file",
                Map.of("directory", "/tmp", "rotation.size.bytes", "-1"),
                10, 1000, 3, 500, true, true);
        assertThrows(IllegalArgumentException.class, config3::validate);

        // Valid file config
        DefaultSinkConfiguration validConfig = new DefaultSinkConfiguration(
                "test", "file",
                Map.of("directory", "/tmp", "format", "JSON"),
                10, 1000, 3, 500, true, true);
        assertDoesNotThrow(validConfig::validate);
    }
}