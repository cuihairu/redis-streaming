package io.github.cuihairu.redis.streaming.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Map;

class MetricsConfigurationTest {

    private MetricsConfiguration configuration;

    @BeforeEach
    void setUp() {
        configuration = new MetricsConfiguration("test-app");
    }

    @Test
    void testDefaultConstructor() {
        MetricsConfiguration config = new MetricsConfiguration("test-app");

        assertEquals("test-app", config.getApplicationName());
        assertTrue(config.isEnabled());
        assertEquals(Duration.ofMinutes(1), config.getReportingInterval());
        assertNotNull(config.getProperties());
    }

    @Test
    void testFullConstructor() {
        Map<String, Object> properties = Map.of("key1", "value1", "key2", 123);
        MetricsConfiguration config = new MetricsConfiguration(
                "full-app",
                false,
                Duration.ofSeconds(30),
                properties
        );

        assertEquals("full-app", config.getApplicationName());
        assertFalse(config.isEnabled());
        assertEquals(Duration.ofSeconds(30), config.getReportingInterval());
        assertEquals("value1", config.getProperty("key1"));
        assertEquals(123, config.getProperty("key2"));
    }

    @Test
    void testPropertyMethods() {
        configuration.setProperty("string.prop", "test");
        configuration.setProperty("int.prop", 42);
        configuration.setProperty("boolean.prop", true);

        assertEquals("test", configuration.getStringProperty("string.prop"));
        assertEquals(42, configuration.getIntProperty("int.prop", 0));
        assertTrue(configuration.getBooleanProperty("boolean.prop", false));
    }

    @Test
    void testPropertyDefaults() {
        assertNull(configuration.getProperty("nonexistent"));
        assertEquals("default", configuration.getProperty("nonexistent", "default"));
        assertEquals("default", configuration.getStringProperty("nonexistent", "default"));
        assertEquals(99, configuration.getIntProperty("nonexistent", 99));
        assertFalse(configuration.getBooleanProperty("nonexistent", false));
    }

    @Test
    void testPropertyTypeConversion() {
        configuration.setProperty("number.string", "123");
        configuration.setProperty("boolean.string", "true");

        assertEquals(123, configuration.getIntProperty("number.string", 0));
        assertEquals(123L, configuration.getLongProperty("number.string", 0L));
        assertTrue(configuration.getBooleanProperty("boolean.string", false));
    }

    @Test
    void testValidateSuccess() {
        assertDoesNotThrow(() -> configuration.validate());
    }

    @Test
    void testValidateFailsWithNullName() {
        MetricsConfiguration invalidConfig = new MetricsConfiguration(
                null, true, Duration.ofMinutes(1), Map.of()
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                invalidConfig::validate
        );
        assertTrue(exception.getMessage().contains("Application name"));
    }

    @Test
    void testValidateFailsWithEmptyName() {
        MetricsConfiguration invalidConfig = new MetricsConfiguration(
                "", true, Duration.ofMinutes(1), Map.of()
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                invalidConfig::validate
        );
        assertTrue(exception.getMessage().contains("Application name"));
    }

    @Test
    void testValidateFailsWithNullInterval() {
        MetricsConfiguration invalidConfig = new MetricsConfiguration(
                "test", true, null, Map.of()
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                invalidConfig::validate
        );
        assertTrue(exception.getMessage().contains("Reporting interval"));
    }

    @Test
    void testValidateFailsWithNegativeInterval() {
        MetricsConfiguration invalidConfig = new MetricsConfiguration(
                "test", true, Duration.ofSeconds(-1), Map.of()
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                invalidConfig::validate
        );
        assertTrue(exception.getMessage().contains("Reporting interval"));
    }

    @Test
    void testWithMethods() {
        MetricsConfiguration modified = configuration
                .withApplicationName("new-app")
                .withEnabled(false)
                .withReportingInterval(Duration.ofSeconds(30));

        assertEquals("new-app", modified.getApplicationName());
        assertFalse(modified.isEnabled());
        assertEquals(Duration.ofSeconds(30), modified.getReportingInterval());

        // Original should be unchanged
        assertEquals("test-app", configuration.getApplicationName());
        assertTrue(configuration.isEnabled());
        assertEquals(Duration.ofMinutes(1), configuration.getReportingInterval());
    }

    @Test
    void testToString() {
        String result = configuration.toString();
        assertTrue(result.contains("test-app"));
        assertTrue(result.contains("enabled=true"));
    }
}