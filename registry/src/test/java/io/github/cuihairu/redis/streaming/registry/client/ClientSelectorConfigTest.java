package io.github.cuihairu.redis.streaming.registry.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClientSelectorConfig
 */
class ClientSelectorConfigTest {

    private ClientSelectorConfig config;

    @BeforeEach
    void setUp() {
        config = new ClientSelectorConfig();
    }

    @Test
    void testConstructor() {
        assertNotNull(config);
    }

    @Test
    void testDefaultValues() {
        assertTrue(config.isEnableFallback());
        assertTrue(config.isFallbackDropMetricsFiltersFirst());
        assertTrue(config.isFallbackDropMetadataFiltersNext());
        assertTrue(config.isFallbackUseAllHealthyLast());
    }

    @Test
    void testSetEnableFallback() {
        config.setEnableFallback(false);
        assertFalse(config.isEnableFallback());

        config.setEnableFallback(true);
        assertTrue(config.isEnableFallback());
    }

    @Test
    void testSetFallbackDropMetricsFiltersFirst() {
        config.setFallbackDropMetricsFiltersFirst(false);
        assertFalse(config.isFallbackDropMetricsFiltersFirst());

        config.setFallbackDropMetricsFiltersFirst(true);
        assertTrue(config.isFallbackDropMetricsFiltersFirst());
    }

    @Test
    void testSetFallbackDropMetadataFiltersNext() {
        config.setFallbackDropMetadataFiltersNext(false);
        assertFalse(config.isFallbackDropMetadataFiltersNext());

        config.setFallbackDropMetadataFiltersNext(true);
        assertTrue(config.isFallbackDropMetadataFiltersNext());
    }

    @Test
    void testSetFallbackUseAllHealthyLast() {
        config.setFallbackUseAllHealthyLast(false);
        assertFalse(config.isFallbackUseAllHealthyLast());

        config.setFallbackUseAllHealthyLast(true);
        assertTrue(config.isFallbackUseAllHealthyLast());
    }

    @Test
    void testDisableAllFallbacks() {
        config.setEnableFallback(false);
        config.setFallbackDropMetricsFiltersFirst(false);
        config.setFallbackDropMetadataFiltersNext(false);
        config.setFallbackUseAllHealthyLast(false);

        assertFalse(config.isEnableFallback());
        assertFalse(config.isFallbackDropMetricsFiltersFirst());
        assertFalse(config.isFallbackDropMetadataFiltersNext());
        assertFalse(config.isFallbackUseAllHealthyLast());
    }

    @Test
    void testEnableOnlyFirstFallback() {
        config.setFallbackDropMetricsFiltersFirst(true);
        config.setFallbackDropMetadataFiltersNext(false);
        config.setFallbackUseAllHealthyLast(false);

        assertTrue(config.isEnableFallback());
        assertTrue(config.isFallbackDropMetricsFiltersFirst());
        assertFalse(config.isFallbackDropMetadataFiltersNext());
        assertFalse(config.isFallbackUseAllHealthyLast());
    }

    @Test
    void testEnableOnlyMetricsFilterFallback() {
        config.setFallbackDropMetricsFiltersFirst(true);
        config.setFallbackDropMetadataFiltersNext(false);
        config.setFallbackUseAllHealthyLast(false);

        assertTrue(config.isFallbackDropMetricsFiltersFirst());
        assertFalse(config.isFallbackDropMetadataFiltersNext());
        assertFalse(config.isFallbackUseAllHealthyLast());
    }

    @Test
    void testEnableOnlyMetadataFilterFallback() {
        config.setFallbackDropMetricsFiltersFirst(false);
        config.setFallbackDropMetadataFiltersNext(true);
        config.setFallbackUseAllHealthyLast(false);

        assertFalse(config.isFallbackDropMetricsFiltersFirst());
        assertTrue(config.isFallbackDropMetadataFiltersNext());
        assertFalse(config.isFallbackUseAllHealthyLast());
    }

    @Test
    void testEnableOnlyAllHealthyFallback() {
        config.setFallbackDropMetricsFiltersFirst(false);
        config.setFallbackDropMetadataFiltersNext(false);
        config.setFallbackUseAllHealthyLast(true);

        assertFalse(config.isFallbackDropMetricsFiltersFirst());
        assertFalse(config.isFallbackDropMetadataFiltersNext());
        assertTrue(config.isFallbackUseAllHealthyLast());
    }

    @Test
    void testMultipleSetters() {
        config.setEnableFallback(false);
        config.setFallbackDropMetricsFiltersFirst(false);
        config.setFallbackDropMetadataFiltersNext(false);
        config.setFallbackUseAllHealthyLast(false);

        // Reset to defaults
        config.setEnableFallback(true);
        config.setFallbackDropMetricsFiltersFirst(true);
        config.setFallbackDropMetadataFiltersNext(true);
        config.setFallbackUseAllHealthyLast(true);

        assertTrue(config.isEnableFallback());
        assertTrue(config.isFallbackDropMetricsFiltersFirst());
        assertTrue(config.isFallbackDropMetadataFiltersNext());
        assertTrue(config.isFallbackUseAllHealthyLast());
    }

    @Test
    void testFallbackIndependentOfEnableFlag() {
        // Even with enableFallback=false, the individual flags can be set
        config.setEnableFallback(false);
        config.setFallbackDropMetricsFiltersFirst(true);

        assertFalse(config.isEnableFallback());
        assertTrue(config.isFallbackDropMetricsFiltersFirst());
    }

    @Test
    void testConfigWithFallbackDisabled() {
        config.setEnableFallback(false);

        // With fallback disabled, individual fallback steps should still be configurable
        config.setFallbackDropMetricsFiltersFirst(true);
        config.setFallbackDropMetadataFiltersNext(true);
        config.setFallbackUseAllHealthyLast(true);

        assertFalse(config.isEnableFallback());
        assertTrue(config.isFallbackDropMetricsFiltersFirst());
        assertTrue(config.isFallbackDropMetadataFiltersNext());
        assertTrue(config.isFallbackUseAllHealthyLast());
    }

    @Test
    void testAllCombinations() {
        // Test different combinations
        config.setEnableFallback(true);
        config.setFallbackDropMetricsFiltersFirst(true);
        config.setFallbackDropMetadataFiltersNext(true);
        config.setFallbackUseAllHealthyLast(true);
        assertTrue(config.isEnableFallback());

        config.setFallbackDropMetricsFiltersFirst(false);
        assertFalse(config.isFallbackDropMetricsFiltersFirst());

        config.setFallbackDropMetadataFiltersNext(false);
        assertFalse(config.isFallbackDropMetadataFiltersNext());

        config.setFallbackUseAllHealthyLast(false);
        assertFalse(config.isFallbackUseAllHealthyLast());
    }
}
