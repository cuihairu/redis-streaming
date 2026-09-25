package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary tests for {@link CDCConfiguration#validate()}: the builder rejects missing names, and
 * the built configuration keeps enforcing the same contract through {@code validate()}.
 */
class CDCConfigValidationCoverageTest {

    private static CDCConfiguration rawConfig(String name) throws Exception {
        Class<?> cls = Class.forName(
                "io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder$DefaultCDCConfiguration");
        Constructor<?> ctor = cls.getDeclaredConstructor(
                String.class, String.class, String.class, int.class, long.class, Map.class);
        ctor.setAccessible(true);
        return (CDCConfiguration) ctor.newInstance(name, "user", "pass", 10, 0L, Map.of());
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
}
