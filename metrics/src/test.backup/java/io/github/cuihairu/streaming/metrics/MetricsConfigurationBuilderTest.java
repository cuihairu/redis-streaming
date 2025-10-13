package io.github.cuihairu.redis.streaming.metrics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Map;

class MetricsConfigurationBuilderTest {

    @Test
    void testBasicBuild() {
        MetricsConfiguration config = new MetricsConfigurationBuilder()
                .applicationName("test-app")
                .enabled(true)
                .reportingInterval(Duration.ofSeconds(30))
                .build();

        assertEquals("test-app", config.getApplicationName());
        assertTrue(config.isEnabled());
        assertEquals(Duration.ofSeconds(30), config.getReportingInterval());
    }

    @Test
    void testBuildWithProperties() {
        MetricsConfiguration config = new MetricsConfigurationBuilder()
                .applicationName("prop-app")
                .property("custom.key", "custom.value")
                .property("number.prop", 42)
                .properties(Map.of("batch.key", "batch.value"))
                .build();

        assertEquals("custom.value", config.getProperty("custom.key"));
        assertEquals(42, config.getProperty("number.prop"));
        assertEquals("batch.value", config.getProperty("batch.key"));
    }

    @Test
    void testPrometheusSpecificConfig() {
        MetricsConfiguration config = new MetricsConfigurationBuilder()
                .applicationName("prometheus-app")
                .prometheusStep(Duration.ofSeconds(15))
                .commonTags("env=prod,service=api")
                .commonTag("region", "us-west-2")
                .registryType("prometheus")
                .jvmMetrics(true)
                .systemMetrics(true)
                .build();

        assertEquals("prometheus", config.getProperty("registry.type"));
        assertTrue(config.getBooleanProperty("jvm.metrics.enabled", false));
        assertTrue(config.getBooleanProperty("system.metrics.enabled", false));
        assertEquals("PT15S", config.getProperty("prometheus.step"));

        String commonTags = config.getStringProperty("common.tags");
        assertTrue(commonTags.contains("env=prod"));
        assertTrue(commonTags.contains("service=api"));
        assertTrue(commonTags.contains("region=us-west-2"));
    }

    @Test
    void testExportConfiguration() {
        MetricsConfiguration config = new MetricsConfigurationBuilder()
                .applicationName("export-app")
                .exportEndpoint("http://metrics.example.com/api/metrics")
                .exportFormat("prometheus")
                .build();

        assertEquals("http://metrics.example.com/api/metrics", config.getProperty("export.endpoint"));
        assertEquals("prometheus", config.getProperty("export.format"));
    }

    @Test
    void testFactoryMethods() {
        MetricsConfiguration prometheusConfig = MetricsConfigurationBuilder
                .forPrometheus("prometheus-app")
                .build();

        assertEquals("prometheus-app", prometheusConfig.getApplicationName());
        assertEquals("prometheus", prometheusConfig.getProperty("registry.type"));
        assertTrue(prometheusConfig.getBooleanProperty("jvm.metrics.enabled", false));
        assertTrue(prometheusConfig.getBooleanProperty("system.metrics.enabled", false));

        MetricsConfiguration simpleConfig = MetricsConfigurationBuilder
                .forSimple("simple-app")
                .build();

        assertEquals("simple-app", simpleConfig.getApplicationName());
        assertEquals("simple", simpleConfig.getProperty("registry.type"));

        MetricsConfiguration micrometerConfig = MetricsConfigurationBuilder
                .forMicrometer("micrometer-app")
                .build();

        assertEquals("micrometer-app", micrometerConfig.getApplicationName());
        assertEquals("micrometer", micrometerConfig.getProperty("registry.type"));
        assertTrue(micrometerConfig.getBooleanProperty("jvm.metrics.enabled", false));

        MetricsConfiguration minimalConfig = MetricsConfigurationBuilder
                .minimal("minimal-app")
                .build();

        assertEquals("minimal-app", minimalConfig.getApplicationName());
        assertTrue(minimalConfig.isEnabled());
        assertEquals(Duration.ofMinutes(5), minimalConfig.getReportingInterval());
    }

    @Test
    void testCommonTagAccumulation() {
        MetricsConfiguration config = new MetricsConfigurationBuilder()
                .applicationName("tag-app")
                .commonTag("env", "prod")
                .commonTag("service", "api")
                .commonTag("version", "1.0")
                .build();

        String commonTags = config.getStringProperty("common.tags");
        assertTrue(commonTags.contains("env=prod"));
        assertTrue(commonTags.contains("service=api"));
        assertTrue(commonTags.contains("version=1.0"));
    }

    @Test
    void testCommonTagWithExisting() {
        MetricsConfiguration config = new MetricsConfigurationBuilder()
                .applicationName("tag-app")
                .commonTags("existing=tag")
                .commonTag("new", "tag")
                .build();

        String commonTags = config.getStringProperty("common.tags");
        assertTrue(commonTags.contains("existing=tag"));
        assertTrue(commonTags.contains("new=tag"));
    }

    @Test
    void testBuildWithoutApplicationName() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new MetricsConfigurationBuilder()
                        .enabled(true)
                        .build()
        );

        assertTrue(exception.getMessage().contains("Application name is required"));
    }

    @Test
    void testBuildWithEmptyApplicationName() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new MetricsConfigurationBuilder()
                        .applicationName("   ")
                        .build()
        );

        assertTrue(exception.getMessage().contains("Application name is required"));
    }

    @Test
    void testBuildWithNullReportingInterval() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new MetricsConfigurationBuilder()
                        .applicationName("test")
                        .reportingInterval(null)
                        .build()
        );

        assertTrue(exception.getMessage().contains("Reporting interval cannot be null"));
    }

    @Test
    void testDefaultValues() {
        MetricsConfiguration config = new MetricsConfigurationBuilder()
                .applicationName("default-app")
                .build();

        assertEquals("default-app", config.getApplicationName());
        assertTrue(config.isEnabled()); // Default enabled
        assertEquals(Duration.ofMinutes(1), config.getReportingInterval()); // Default interval
    }
}