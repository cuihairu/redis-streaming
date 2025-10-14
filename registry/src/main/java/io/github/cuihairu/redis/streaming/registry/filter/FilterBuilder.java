package io.github.cuihairu.redis.streaming.registry.filter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fluent builder for metadata and metrics filters.
 * Produces maps compatible with server-side Lua filters.
 */
public class FilterBuilder {
    private final Map<String, String> metadata = new LinkedHashMap<>();
    private final Map<String, String> metrics = new LinkedHashMap<>();

    public static FilterBuilder create() {
        return new FilterBuilder();
    }

    private static String op(String key, String operator) {
        return operator == null || operator.isEmpty() ? key : (key + ":" + operator);
    }

    // -------- metadata --------
    public FilterBuilder metaEq(String key, String value) { metadata.put(op(key, "=="), value); return this; }
    public FilterBuilder metaNe(String key, String value) { metadata.put(op(key, "!="), value); return this; }
    public FilterBuilder metaGt(String key, Number value) { metadata.put(op(key, ">"), String.valueOf(value)); return this; }
    public FilterBuilder metaGte(String key, Number value) { metadata.put(op(key, ">="), String.valueOf(value)); return this; }
    public FilterBuilder metaLt(String key, Number value) { metadata.put(op(key, "<"), String.valueOf(value)); return this; }
    public FilterBuilder metaLte(String key, Number value) { metadata.put(op(key, "<="), String.valueOf(value)); return this; }

    // -------- metrics --------
    public FilterBuilder metricEq(String key, String value) { metrics.put(op(key, "=="), value); return this; }
    public FilterBuilder metricNe(String key, String value) { metrics.put(op(key, "!="), value); return this; }
    public FilterBuilder metricGt(String key, Number value) { metrics.put(op(key, ">"), String.valueOf(value)); return this; }
    public FilterBuilder metricGte(String key, Number value) { metrics.put(op(key, ">="), String.valueOf(value)); return this; }
    public FilterBuilder metricLt(String key, Number value) { metrics.put(op(key, "<"), String.valueOf(value)); return this; }
    public FilterBuilder metricLte(String key, Number value) { metrics.put(op(key, "<="), String.valueOf(value)); return this; }

    public Map<String, String> buildMetadata() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public Map<String, String> buildMetrics() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }
}

