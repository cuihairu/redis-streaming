package io.github.cuihairu.redis.streaming.source;

import io.github.cuihairu.redis.streaming.source.impl.DefaultSourceConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SourceConnectorFactoryTest {

    private SourceConnectorFactory factory;

    @BeforeEach
    void setUp() {
        factory = new SourceConnectorFactory();
    }

    @Test
    void testRegisteredConnectorTypes() {
        var registeredTypes = factory.getRegisteredTypes();

        assertTrue(registeredTypes.contains("http"));
        assertTrue(registeredTypes.contains("file"));
        assertTrue(registeredTypes.contains("iot"));
    }

    @Test
    void testCreateHttpConnector() {
        SourceConfiguration config = DefaultSourceConfiguration.builder()
                .name("test-http")
                .type("http")
                .property("url", "http://example.com")
                .build();

        SourceConnector connector = factory.createConnector(config);

        assertNotNull(connector);
        assertEquals("test-http", connector.getName());
        assertEquals(config, connector.getConfiguration());
    }

    @Test
    void testCreateFileConnector() {
        SourceConfiguration config = DefaultSourceConfiguration.builder()
                .name("test-file")
                .type("file")
                .property("directory", "/tmp")
                .build();

        SourceConnector connector = factory.createConnector(config);

        assertNotNull(connector);
        assertEquals("test-file", connector.getName());
    }

    @Test
    void testCreateIoTConnector() {
        SourceConfiguration config = DefaultSourceConfiguration.builder()
                .name("test-iot")
                .type("iot")
                .property("device.count", 5)
                .build();

        SourceConnector connector = factory.createConnector(config);

        assertNotNull(connector);
        assertEquals("test-iot", connector.getName());
    }

    @Test
    void testCreateUnknownConnectorType() {
        SourceConfiguration config = DefaultSourceConfiguration.builder()
                .name("test-unknown")
                .type("unknown")
                .build();

        assertThrows(IllegalArgumentException.class, () -> {
            factory.createConnector(config);
        });
    }

    @Test
    void testHttpConnectorConvenienceMethod() {
        SourceConnector connector = factory.createHttpConnector("test", "http://example.com");

        assertNotNull(connector);
        assertEquals("test", connector.getName());
        assertEquals("http", connector.getConfiguration().getType());
    }

    @Test
    void testHttpConnectorWithCustomConfig() {
        Map<String, String> headers = Map.of("Authorization", "Bearer token");

        SourceConnector connector = factory.createHttpConnector(
                "test", "http://example.com", "POST", headers, 5000);

        assertNotNull(connector);
        assertEquals("test", connector.getName());
        assertEquals("POST", connector.getConfiguration().getProperty("method"));
        assertEquals(5000L, connector.getConfiguration().getPollingIntervalMs());
    }

    @Test
    void testFileConnectorConvenienceMethod() {
        SourceConnector connector = factory.createFileConnector("test", "/tmp", "*.txt");

        assertNotNull(connector);
        assertEquals("test", connector.getName());
        assertEquals("file", connector.getConfiguration().getType());
        assertEquals("/tmp", connector.getConfiguration().getProperty("directory"));
        assertEquals("*.txt", connector.getConfiguration().getProperty("file.pattern"));
    }

    @Test
    void testFileConnectorWithCustomConfig() {
        SourceConnector connector = factory.createFileConnector(
                "test", "/tmp", "*.log", "modify", true);

        assertNotNull(connector);
        assertEquals("modify", connector.getConfiguration().getProperty("watch.mode"));
        assertEquals(true, connector.getConfiguration().getProperty("read.existing"));
    }

    @Test
    void testIoTConnectorConvenienceMethod() {
        SourceConnector connector = factory.createIoTConnector("test", 10, "temperature,humidity");

        assertNotNull(connector);
        assertEquals("test", connector.getName());
        assertEquals("iot", connector.getConfiguration().getType());
        assertEquals(10, connector.getConfiguration().getProperty("device.count"));
        assertEquals("temperature,humidity", connector.getConfiguration().getProperty("device.types"));
    }

    @Test
    void testIoTConnectorWithCustomConfig() {
        SourceConnector connector = factory.createIoTConnector(
                "test", 5, "pressure", 2000L, false);

        assertNotNull(connector);
        assertEquals(2000L, connector.getConfiguration().getProperty("data.interval.ms"));
        assertEquals(false, connector.getConfiguration().getProperty("simulation.mode"));
    }

    @Test
    void testCustomConnectorRegistration() {
        // Register a custom connector
        factory.registerConnector("custom", config -> new TestCustomConnector(config));

        assertTrue(factory.getRegisteredTypes().contains("custom"));

        SourceConfiguration config = DefaultSourceConfiguration.builder()
                .name("test-custom")
                .type("custom")
                .build();

        SourceConnector connector = factory.createConnector(config);
        assertNotNull(connector);
        assertTrue(connector instanceof TestCustomConnector);
    }

    // Test custom connector implementation
    private static class TestCustomConnector implements SourceConnector {
        private final SourceConfiguration configuration;

        public TestCustomConnector(SourceConfiguration configuration) {
            this.configuration = configuration;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> start() {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> stop() {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public java.util.List<SourceRecord> poll() {
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public String getName() {
            return configuration.getName();
        }

        @Override
        public SourceConfiguration getConfiguration() {
            return configuration;
        }

        @Override
        public void setEventListener(SourceEventListener listener) {}

        @Override
        public SourceHealthStatus getHealthStatus() {
            return SourceHealthStatus.unknown("Test connector");
        }
    }
}