package io.github.cuihairu.redis.streaming.registry.metrics;

/**
 * Global holder for provider metrics configuration (used by default MetricsCollectionManager).
 */
public final class MetricsGlobal {
    private static volatile MetricsConfig defaultConfig;

    private MetricsGlobal() {}

    public static void setDefaultConfig(MetricsConfig cfg) {
        defaultConfig = cfg;
    }

    public static MetricsConfig getOrDefault() {
        return defaultConfig != null ? defaultConfig : new MetricsConfig();
    }
}

