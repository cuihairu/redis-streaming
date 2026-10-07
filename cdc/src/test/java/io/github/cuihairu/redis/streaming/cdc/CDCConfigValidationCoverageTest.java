package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary tests for {@link CDCConfiguration#validate()}: the builder rejects missing names and
 * non-positive batchSize / negative pollingIntervalMs (CDC-L2), and the built configuration keeps
 * enforcing the same contract through {@code validate()}. Boolean properties tolerate the string
 * form YAML/JSON configs produce instead of a raw cast.
 */
class CDCConfigValidationCoverageTest {

    private static CDCConfiguration rawConfig(String name) throws Exception {
        return rawConfig(name, 10, 0L, Map.of());
    }

    private static CDCConfiguration rawConfig(String name, int batchSize, long pollingIntervalMs,
                                              Map<String, Object> props) throws Exception {
        Class<?> cls = Class.forName(
                "io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder$DefaultCDCConfiguration");
        Constructor<?> ctor = cls.getDeclaredConstructor(
                String.class, String.class, String.class, int.class, long.class, Map.class);
        ctor.setAccessible(true);
        return (CDCConfiguration) ctor.newInstance(name, "user", "pass", batchSize, pollingIntervalMs, props);
    }

    @Test
    void buildRejectsMissingName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CDCConfigurationBuilder().build());
        assertEquals("Connector name is required", e.getMessage());
    }

    @Test
    void buildRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> CDCConfigurationBuilder.forDatabasePolling("   ").build());
    }

    @Test
    void validateRejectsNullName() throws Exception {
        CDCConfiguration cfg = rawConfig(null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, cfg::validate);
        assertTrue(e.getMessage().contains("name"));
    }

    @Test
    void validateRejectsBlankName() throws Exception {
        CDCConfiguration cfg = rawConfig("  ");
        assertThrows(IllegalArgumentException.class, cfg::validate);
    }

    @Test
    void validateAcceptsPresentName() throws Exception {
        CDCConfiguration cfg = rawConfig("orders");
        assertDoesNotThrow(cfg::validate);
        assertEquals("orders", cfg.getName());
    }

    @Test
    void validateRejectsZeroBatchSize() throws Exception {
        CDCConfiguration cfg = rawConfig("orders", 0, 0L, Map.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, cfg::validate);
        assertEquals("batchSize must be > 0, got: 0", e.getMessage());
    }

    @Test
    void validateRejectsNegativeBatchSize() throws Exception {
        CDCConfiguration cfg = rawConfig("orders", -5, 0L, Map.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, cfg::validate);
        assertEquals("batchSize must be > 0, got: -5", e.getMessage());
    }

    @Test
    void validateRejectsNegativePollingInterval() throws Exception {
        CDCConfiguration cfg = rawConfig("orders", 10, -1L, Map.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, cfg::validate);
        assertEquals("pollingIntervalMs must be >= 0, got: -1", e.getMessage());
    }

    @Test
    void buildRejectsZeroBatchSize() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CDCConfigurationBuilder.forDatabasePolling("orders")
                        .jdbcUrl("jdbc:h2:mem:test")
                        .tables("t")
                        .batchSize(0)
                        .build());
        assertEquals("batchSize must be > 0, got: 0", e.getMessage());
    }

    @Test
    void booleanPropertyParsesStringTrue() throws Exception {
        CDCConfiguration cfg = rawConfig("orders", 10, 0L, Map.of("auto.start", "true"));
        assertTrue(cfg.isAutoStart());
    }

    @Test
    void booleanPropertyTrimsAndIgnoresCase() throws Exception {
        CDCConfiguration cfg = rawConfig("orders", 10, 0L, Map.of("snapshot.enabled", " FALSE "));
        assertFalse(cfg.isSnapshotEnabled());
    }

    @Test
    void booleanPropertyPassesThroughBooleanValue() throws Exception {
        CDCConfiguration cfg = rawConfig("orders", 10, 0L, Map.of("auto.start", Boolean.TRUE));
        assertTrue(cfg.isAutoStart());
    }

    @Test
    void booleanPropertyDefaultsWhenAbsent() throws Exception {
        CDCConfiguration cfg = rawConfig("orders");
        assertFalse(cfg.isAutoStart());
        assertFalse(cfg.isSnapshotEnabled());
    }

    @Test
    void booleanPropertyRejectsGarbageString() throws Exception {
        CDCConfiguration cfg = rawConfig("orders", 10, 0L, Map.of("auto.start", "sometimes"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, cfg::isAutoStart);
        assertEquals("Property \"auto.start\" must be \"true\" or \"false\", got: sometimes", e.getMessage());
    }

    @Test
    void booleanPropertyRejectsNonStringValue() throws Exception {
        CDCConfiguration cfg = rawConfig("orders", 10, 0L, Map.of("snapshot.enabled", 42));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, cfg::isSnapshotEnabled);
        assertEquals("Property \"snapshot.enabled\" must be a boolean or the string \"true\"/\"false\", got: 42",
                e.getMessage());
    }
}
