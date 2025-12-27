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
                .metaNe("env", "prod")
                .metaGt("weight", 10)
                .metaGte("priority", 5)
                .metaLt("age", 18)
                .metaLte("latency", 100)
                .metricEq("az", "a")
                .metricNe("rack", "r1")
                .metricGt("mem", 1024)
                .metricLt("cpu", 80)
                .metricGte("qps", 1000);

        Map<String, String> md = fb.buildMetadata();
        Map<String, String> mt = fb.buildMetrics();

        assertEquals("us-east", md.get("region:=="));
        assertEquals("prod", md.get("env:!="));
        assertEquals("10", md.get("weight:>"));
        assertEquals("5", md.get("priority:>="));
        assertEquals("18", md.get("age:<"));
        assertEquals("100", md.get("latency:<="));

        assertEquals("a", mt.get("az:=="));
        assertEquals("r1", mt.get("rack:!="));
        assertEquals("1024", mt.get("mem:>"));
        assertEquals("80", mt.get("cpu:<"));
        assertEquals("1000", mt.get("qps:>="));
    }
}
