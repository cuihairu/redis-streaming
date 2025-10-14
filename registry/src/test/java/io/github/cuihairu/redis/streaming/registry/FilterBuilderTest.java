package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.filter.FilterBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FilterBuilderTest {
    @Test
    public void testBuildFilters() {
        FilterBuilder fb = FilterBuilder.create()
                .metaEq("region", "us-east")
                .metaGt("weight", 10)
                .metaLte("latency", 100)
                .metricLt("cpu", 80)
                .metricGte("qps", 1000);

        Map<String, String> md = fb.buildMetadata();
        Map<String, String> mt = fb.buildMetrics();

        assertEquals("us-east", md.get("region:=="));
        assertEquals("10", md.get("weight:>"));
        assertEquals("100", md.get("latency:<="));

        assertEquals("80", mt.get("cpu:<"));
        assertEquals("1000", mt.get("qps:>="));
    }
}

