package io.github.cuihairu.redis.streaming.sink;

import io.github.cuihairu.redis.streaming.sink.impl.DefaultSinkConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SinkConnectorFactoryTest {

    private SinkConnectorFactory factory;

    @BeforeEach
    void setUp() {
        factory = new SinkConnectorFactory();
    }

    @Test
    void testRegisteredConnectorTypes() {
        var registeredTypes = factory.getRegisteredTypes();

        assertTrue(registeredTypes.contains("elasticsearch"));
        assertTrue(registeredTypes.contains("database"));
        assertTrue(registeredTypes.contains("file"));
    }

    @Test
    void testCreateElasticsearchConnector() {
        SinkConfiguration config = DefaultSinkConfiguration.builder()
                .name("test-elasticsearch")
                .type("elasticsearch")
                .property("hosts", "localhost:9200")
                .property("index", "test-index")
                .build();

        SinkConnector connector = factory.createConnector(config);

        assertNotNull(connector);
        assertEquals("test-elasticsearch", connector.getName());
        assertEquals(config, connector.getConfiguration());
    }

    @Test
    void testCreateDatabaseConnector() {
        SinkConfiguration config = DefaultSinkConfiguration.builder()
                .name("test-database")
                .type("database")
                .property("jdbc.url", "jdbc:h2:mem:test")
                .property("username", "sa")
                .property("password", "")
                .build();

        SinkConnector connector = factory.createConnector(config);

        assertNotNull(connector);
        assertEquals("test-database", connector.getName());
    }

    @Test
    void testCreateFileConnector() {
        SinkConfiguration config = DefaultSinkConfiguration.builder()
                .name("test-file")
                .type("file")
                .property("directory", "/tmp")
                .property("format", "JSON")
                .build();

        SinkConnector connector = factory.createConnector(config);

        assertNotNull(connector);
        assertEquals("test-file", connector.getName());
    }

    @Test
    void testCreateUnknownConnectorType() {
        SinkConfiguration config = DefaultSinkConfiguration.builder()
                .name("test-unknown")
                .type("unknown")
                .build();

        assertThrows(IllegalArgumentException.class, () -> {
            factory.createConnector(config);
        });
    }

    @Test
    void testElasticsearchConnectorConvenienceMethod() {
        SinkConnector connector = factory.createElasticsearchConnector(
                "test", "localhost:9200", "test-index");

        assertNotNull(connector);
        assertEquals("test", connector.getName());
        assertEquals("elasticsearch", connector.getConfiguration().getType());
    }

    @Test
    void testElasticsearchConnectorWithCustomConfig() {
        SinkConnector connector = factory.createElasticsearchConnector(
                "test", "localhost:9200", "logs-{date}", "yyyy-MM-dd", 50);

        assertNotNull(connector);
        assertEquals("test", connector.getName());
        assertEquals("logs-{date}", connector.getConfiguration().getProperty("index.pattern"));
        assertEquals("yyyy-MM-dd", connector.getConfiguration().getProperty("date.format"));
        assertEquals(50, connector.getConfiguration().getBatchSize());
    }

    @Test
    void testDatabaseConnectorConvenienceMethod() {
        SinkConnector connector = factory.createDatabaseConnector(
                "test", "jdbc:h2:mem:test", "sa", "", "test_table");

        assertNotNull(connector);
        assertEquals("test", connector.getName());
        assertEquals("database", connector.getConfiguration().getType());
        assertEquals("test_table", connector.getConfiguration().getProperty("table"));
    }

    @Test
    void testDatabaseConnectorWithCustomConfig() {
        SinkConnector connector = factory.createDatabaseConnector(
                "test", "jdbc:h2:mem:test", "sa", "", "test_table",
                "org.h2.Driver", true);

        assertNotNull(connector);
        assertEquals("org.h2.Driver", connector.getConfiguration().getProperty("driver.class"));
        assertEquals(true, connector.getConfiguration().getProperty("auto.create.table"));
    }

    @Test
    void testFileConnectorConvenienceMethod() {
        SinkConnector connector = factory.createFileConnector("test", "/tmp", "JSON");

        assertNotNull(connector);
        assertEquals("test", connector.getName());
        assertEquals("file", connector.getConfiguration().getType());
        assertEquals("/tmp", connector.getConfiguration().getProperty("directory"));
        assertEquals("JSON", connector.getConfiguration().getProperty("format"));
    }

    @Test
    void testFileConnectorWithCustomConfig() {
        SinkConnector connector = factory.createFileConnector(
                "test", "/tmp", "CSV", "data-{date}.csv", 1024000L, false);

        assertNotNull(connector);
        assertEquals("data-{date}.csv", connector.getConfiguration().getProperty("filename.pattern"));
        assertEquals(1024000L, connector.getConfiguration().getProperty("rotation.size.bytes"));
        assertEquals(false, connector.getConfiguration().getProperty("append"));
    }

    @Test
    void testCustomConnectorRegistration() {
        // Register a custom connector
        factory.registerConnector("custom", config -> new TestCustomConnector(config));

        assertTrue(factory.getRegisteredTypes().contains("custom"));

        SinkConfiguration config = DefaultSinkConfiguration.builder()
                .name("test-custom")
                .type("custom")
                .build();

        SinkConnector connector = factory.createConnector(config);
        assertNotNull(connector);
        assertTrue(connector instanceof TestCustomConnector);
    }

    // Test custom connector implementation
    private static class TestCustomConnector implements SinkConnector {
        private final SinkConfiguration configuration;

        public TestCustomConnector(SinkConfiguration configuration) {
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
        public java.util.concurrent.CompletableFuture<SinkResult> write(java.util.Collection<SinkRecord> records) {
            return java.util.concurrent.CompletableFuture.completedFuture(SinkResult.success(records.size()));
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> flush() {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
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
        public SinkConfiguration getConfiguration() {
            return configuration;
        }

        @Override
        public void setEventListener(SinkEventListener listener) {}

        @Override
        public SinkHealthStatus getHealthStatus() {
            return SinkHealthStatus.unknown("Test connector");
        }

        @Override
        public SinkMetrics getMetrics() {
            return new SinkMetrics();
        }
    }
}