package io.github.cuihairu.redis.streaming.metrics;

import io.github.cuihairu.redis.streaming.metrics.impl.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetricsFactoryTest {

    @Test
    void testCreateSimpleCollector() {
        MetricsCollector collector = MetricsFactory.createSimpleCollector("simple-test");

        assertNotNull(collector);
        assertInstanceOf(SimpleMetricsCollector.class, collector);
        assertEquals("simple-test", collector.getName());
    }

    @Test
    void testCreateMicrometerCollector() {
        MetricsCollector collector = MetricsFactory.createMicrometerCollector("micrometer-test");

        assertNotNull(collector);
        assertInstanceOf(MicrometerMetricsCollector.class, collector);
        assertEquals("micrometer-test", collector.getName());
    }

    @Test
    void testCreatePrometheusCollector() {
        MetricsCollector collector = MetricsFactory.createPrometheusCollector("prometheus-test", "test-app");

        assertNotNull(collector);
        assertInstanceOf(PrometheusMetricsCollector.class, collector);
        assertEquals("prometheus-test", collector.getName());
    }

    @Test
    void testCreateCollectorByType() {
        MetricsConfiguration config = new MetricsConfiguration("test-app");

        MetricsCollector simpleCollector = MetricsFactory.createCollector(
                MetricsFactory.CollectorType.SIMPLE, "simple", config);
        assertInstanceOf(SimpleMetricsCollector.class, simpleCollector);

        MetricsCollector micrometerCollector = MetricsFactory.createCollector(
                MetricsFactory.CollectorType.MICROMETER, "micrometer", config);
        assertInstanceOf(MicrometerMetricsCollector.class, micrometerCollector);

        MetricsCollector prometheusCollector = MetricsFactory.createCollector(
                MetricsFactory.CollectorType.PROMETHEUS, "prometheus", config);
        assertInstanceOf(PrometheusMetricsCollector.class, prometheusCollector);
    }

    @Test
    void testCreateCollectorByTypeName() {
        MetricsConfiguration config = new MetricsConfiguration("test-app");

        MetricsCollector simpleCollector = MetricsFactory.createCollector("SIMPLE", "simple", config);
        assertInstanceOf(SimpleMetricsCollector.class, simpleCollector);

        MetricsCollector lowercaseCollector = MetricsFactory.createCollector("prometheus", "prometheus", config);
        assertInstanceOf(PrometheusMetricsCollector.class, lowercaseCollector);
    }

    @Test
    void testCreateCollectorByConfigRegistryType() {
        MetricsConfiguration config = new MetricsConfiguration("test-app");
        config.setProperty("registry.type", "prometheus");

        MetricsCollector collector = MetricsFactory.createCollector("unknown", "test", config);
        assertInstanceOf(PrometheusMetricsCollector.class, collector);
    }

    @Test
    void testCreateCollectorWithInvalidType() {
        MetricsConfiguration config = new MetricsConfiguration("test-app");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> MetricsFactory.createCollector("INVALID", "test", config)
        );

        assertTrue(exception.getMessage().contains("Unknown collector type"));
    }

    @Test
    void testCreateRegistry() {
        MetricsRegistry registry = MetricsFactory.createRegistry("test-app");

        assertNotNull(registry);
        assertInstanceOf(DefaultMetricsRegistry.class, registry);
        assertEquals("test-app", registry.getConfiguration().getApplicationName());
    }

    @Test
    void testCreateRegistryWithConfiguration() {
        MetricsConfiguration config = new MetricsConfiguration("custom-app", false);
        MetricsRegistry registry = MetricsFactory.createRegistry(config);

        assertNotNull(registry);
        assertEquals(config, registry.getConfiguration());
        assertEquals("custom-app", registry.getConfiguration().getApplicationName());
        assertFalse(registry.getConfiguration().isEnabled());
    }

    @Test
    void testCreateSetup() {
        MetricsFactory.MetricsSetup setup = MetricsFactory.createSetup(
                MetricsFactory.CollectorType.SIMPLE,
                "test-collector",
                "test-app"
        );

        assertNotNull(setup);
        assertNotNull(setup.getRegistry());
        assertNotNull(setup.getCollector());
        assertNotNull(setup.getConfiguration());

        assertEquals("test-collector", setup.getCollector().getName());
        assertEquals("test-app", setup.getConfiguration().getApplicationName());
        assertEquals(1, setup.getRegistry().getCollectorCount());
    }

    @Test
    void testCreatePrometheusSetup() {
        MetricsFactory.MetricsSetup setup = MetricsFactory.createPrometheusSetup("prometheus-collector", "prometheus-app");

        assertNotNull(setup);
        assertInstanceOf(PrometheusMetricsCollector.class, setup.getCollector());
        assertEquals("prometheus-collector", setup.getCollector().getName());
        assertEquals("prometheus-app", setup.getConfiguration().getApplicationName());
    }

    @Test
    void testCreateSimpleSetup() {
        MetricsFactory.MetricsSetup setup = MetricsFactory.createSimpleSetup("simple-collector", "simple-app");

        assertNotNull(setup);
        assertInstanceOf(SimpleMetricsCollector.class, setup.getCollector());
        assertEquals("simple-collector", setup.getCollector().getName());
        assertEquals("simple-app", setup.getConfiguration().getApplicationName());
    }

    @Test
    void testMetricsSetupFunctionality() {
        MetricsFactory.MetricsSetup setup = MetricsFactory.createPrometheusSetup("test", "test-app");

        // Test setting enabled state
        setup.setEnabled(false);
        assertFalse(setup.getCollector().isEnabled());

        setup.setEnabled(true);
        assertTrue(setup.getCollector().isEnabled());

        // Test Prometheus metrics
        String prometheusMetrics = setup.getPrometheusMetrics();
        assertNotNull(prometheusMetrics);
    }

    @Test
    void testMetricsSetupWithNonPrometheusCollector() {
        MetricsFactory.MetricsSetup setup = MetricsFactory.createSimpleSetup("simple", "test-app");

        // Should return empty string for non-Prometheus collectors
        String prometheusMetrics = setup.getPrometheusMetrics();
        assertEquals("", prometheusMetrics);
    }

    @Test
    void testCollectorTypeEnum() {
        assertEquals(3, MetricsFactory.CollectorType.values().length);
        assertNotNull(MetricsFactory.CollectorType.valueOf("SIMPLE"));
        assertNotNull(MetricsFactory.CollectorType.valueOf("MICROMETER"));
        assertNotNull(MetricsFactory.CollectorType.valueOf("PROMETHEUS"));
    }
}