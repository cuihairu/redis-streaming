package io.github.cuihairu.redis.streaming.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the default methods declared on {@code ConfigService}. */
class ConfigInterfaceDefaultsCoverageTest {

    @Test
    void configServiceDefaultsAreInert() {
        ConfigService service = new ConfigService() {
            @Override
            public String getConfig(String dataId, String group) {
                return dataId.equals("present") ? "value" : null;
            }
            @Override
            public boolean publishConfig(String dataId, String group, String content) {
                return true;
            }
            @Override
            public boolean publishConfig(String dataId, String group, String content, String description) {
                return true;
            }
            @Override
            public boolean removeConfig(String dataId, String group) {
                return true;
            }
            @Override
            public List<ConfigHistory> getConfigHistory(String dataId, String group, int size) {
                return List.of();
            }
            @Override
            public void addListener(String dataId, String group, ConfigChangeListener listener) {
            }
            @Override
            public void removeListener(String dataId, String group, ConfigChangeListener listener) {
            }
            @Override
            public void start() {
            }
            @Override
            public void stop() {
            }
        };

        assertEquals(0, service.trimHistoryBySize("d", "g", 5));
        assertEquals(0, service.trimHistoryByAge("d", "g", Duration.ofDays(1)));
        assertFalse(service.isRunning());
        assertTrue(service.hasConfig("present", "g"));
        assertFalse(service.hasConfig("absent", "g"));

        assertEquals("value", service.getConfig("present", "g", "fallback"));
        assertEquals("fallback", service.getConfig("absent", "g", "fallback"));
    }
}
