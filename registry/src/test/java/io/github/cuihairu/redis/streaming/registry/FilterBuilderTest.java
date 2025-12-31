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

    @Test
    void createReturnsNewInstance() {
        FilterBuilder fb1 = FilterBuilder.create();
        FilterBuilder fb2 = FilterBuilder.create();

        assertNotNull(fb1);
        assertNotNull(fb2);
        assertNotSame(fb1, fb2);
    }

    @Test
    void metaEqAddsOperatorSuffix() {
        FilterBuilder fb = FilterBuilder.create()
                .metaEq("key", "value");

        Map<String, String> md = fb.buildMetadata();
        assertEquals("value", md.get("key:=="));
        assertEquals(1, md.size());
    }

    @Test
    void metaNeAddsOperatorSuffix() {
        FilterBuilder fb = FilterBuilder.create()
                .metaNe("key", "value");

        Map<String, String> md = fb.buildMetadata();
        assertEquals("value", md.get("key:!="));
        assertEquals(1, md.size());
    }

    @Test
    void metaGtAddsOperatorSuffix() {
        FilterBuilder fb = FilterBuilder.create()
                .metaGt("count", 100);

        Map<String, String> md = fb.buildMetadata();
        assertEquals("100", md.get("count:>"));
        assertEquals(1, md.size());
    }

    @Test
    void metaGteAddsOperatorSuffix() {
        FilterBuilder fb = FilterBuilder.create()
                .metaGte("min", 50);

        Map<String, String> md = fb.buildMetadata();
        assertEquals("50", md.get("min:>="));
        assertEquals(1, md.size());
    }

    @Test
    void metaLtAddsOperatorSuffix() {
        FilterBuilder fb = FilterBuilder.create()
                .metaLt("delay", 200);

        Map<String, String> md = fb.buildMetadata();
        assertEquals("200", md.get("delay:<"));
        assertEquals(1, md.size());
    }

    @Test
    void metaLteAddsOperatorSuffix() {
        FilterBuilder fb = FilterBuilder.create()
                .metaLte("max", 500);

        Map<String, String> md = fb.buildMetadata();
        assertEquals("500", md.get("max:<="));
        assertEquals(1, md.size());
    }

    @Test
    void metricEqAddsOperatorSuffix() {
        FilterBuilder fb = FilterBuilder.create()
                .metricEq("host", "server1");

        Map<String, String> mt = fb.buildMetrics();
        assertEquals("server1", mt.get("host:=="));
        assertEquals(1, mt.size());
    }

    @Test
    void metricNeAddsOperatorSuffix() {
        FilterBuilder fb = FilterBuilder.create()
                .metricNe("status", "offline");

        Map<String, String> mt = fb.buildMetrics();
        assertEquals("offline", mt.get("status:!="));
        assertEquals(1, mt.size());
    }

    @Test
    void metricGtAddsOperatorSuffix() {
        FilterBuilder fb = FilterBuilder.create()
                .metricGt("temperature", 80);

        Map<String, String> mt = fb.buildMetrics();
        assertEquals("80", mt.get("temperature:>"));
        assertEquals(1, mt.size());
    }

    @Test
    void metricGteAddsOperatorSuffix() {
        FilterBuilder fb = FilterBuilder.create()
                .metricGte("uptime", 1000);

        Map<String, String> mt = fb.buildMetrics();
        assertEquals("1000", mt.get("uptime:>="));
        assertEquals(1, mt.size());
    }

    @Test
    void metricLtAddsOperatorSuffix() {
        FilterBuilder fb = FilterBuilder.create()
                .metricLt("errors", 5);

        Map<String, String> mt = fb.buildMetrics();
        assertEquals("5", mt.get("errors:<"));
        assertEquals(1, mt.size());
    }

    @Test
    void metricLteAddsOperatorSuffix() {
        FilterBuilder fb = FilterBuilder.create()
                .metricLte("connections", 100);

        Map<String, String> mt = fb.buildMetrics();
        assertEquals("100", mt.get("connections:<="));
        assertEquals(1, mt.size());
    }

    @Test
    void buildMetadataReturnsUnmodifiableMap() {
        FilterBuilder fb = FilterBuilder.create()
                .metaEq("key", "value");

        Map<String, String> md = fb.buildMetadata();
        assertThrows(UnsupportedOperationException.class, () -> md.put("new", "value"));
        assertThrows(UnsupportedOperationException.class, () -> md.remove("key:=="));
        assertThrows(UnsupportedOperationException.class, () -> md.clear());
    }

    @Test
    void buildMetricsReturnsUnmodifiableMap() {
        FilterBuilder fb = FilterBuilder.create()
                .metricEq("key", "value");

        Map<String, String> mt = fb.buildMetrics();
        assertThrows(UnsupportedOperationException.class, () -> mt.put("new", "value"));
        assertThrows(UnsupportedOperationException.class, () -> mt.remove("key:=="));
        assertThrows(UnsupportedOperationException.class, () -> mt.clear());
    }

    @Test
    void buildMetadataPreservesInsertionOrder() {
        FilterBuilder fb = FilterBuilder.create()
                .metaEq("first", "1")
                .metaEq("second", "2")
                .metaEq("third", "3");

        Map<String, String> md = fb.buildMetadata();
        var keys = md.keySet().iterator();
        assertEquals("first:==", keys.next());
        assertEquals("second:==", keys.next());
        assertEquals("third:==", keys.next());
    }

    @Test
    void buildMetricsPreservesInsertionOrder() {
        FilterBuilder fb = FilterBuilder.create()
                .metricEq("first", "1")
                .metricEq("second", "2")
                .metricEq("third", "3");

        Map<String, String> mt = fb.buildMetrics();
        var keys = mt.keySet().iterator();
        assertEquals("first:==", keys.next());
        assertEquals("second:==", keys.next());
        assertEquals("third:==", keys.next());
    }

    @Test
    void chainingReturnsSameInstance() {
        FilterBuilder fb = FilterBuilder.create();
        FilterBuilder result = fb.metaEq("key", "value").metaNe("key2", "value2");

        assertSame(fb, result);
    }

    @Test
    void emptyBuilderReturnsEmptyMaps() {
        FilterBuilder fb = FilterBuilder.create();

        Map<String, String> md = fb.buildMetadata();
        Map<String, String> mt = fb.buildMetrics();

        assertTrue(md.isEmpty());
        assertTrue(mt.isEmpty());
    }

    @Test
    void metadataAndMetricsAreSeparate() {
        FilterBuilder fb = FilterBuilder.create()
                .metaEq("key1", "value1")
                .metricEq("key2", "value2");

        Map<String, String> md = fb.buildMetadata();
        Map<String, String> mt = fb.buildMetrics();

        assertEquals(1, md.size());
        assertEquals(1, mt.size());
        assertTrue(md.containsKey("key1:=="));
        assertTrue(mt.containsKey("key2:=="));
        assertFalse(md.containsKey("key2:=="));
        assertFalse(mt.containsKey("key1:=="));
    }

    @Test
    void multipleFiltersOfSameType() {
        FilterBuilder fb = FilterBuilder.create()
                .metaEq("key1", "value1")
                .metaGt("key2", 100)
                .metaLt("key3", 200);

        Map<String, String> md = fb.buildMetadata();
        assertEquals(3, md.size());
        assertEquals("value1", md.get("key1:=="));
        assertEquals("100", md.get("key2:>"));
        assertEquals("200", md.get("key3:<"));
    }

    @Test
    void multipleFiltersOfDifferentTypes() {
        FilterBuilder fb = FilterBuilder.create()
                .metaEq("m1", "v1")
                .metricEq("n1", "w1")
                .metaGt("m2", 10)
                .metricLt("n2", 20);

        Map<String, String> md = fb.buildMetadata();
        Map<String, String> mt = fb.buildMetrics();

        assertEquals(2, md.size());
        assertEquals(2, mt.size());
    }

    @Test
    void numericValuesAreConvertedToString() {
        FilterBuilder fb = FilterBuilder.create()
                .metaGt("int", 42)
                .metaGt("long", 10000000000L)
                .metaGt("double", 3.14)
                .metaGt("float", 2.5f);

        Map<String, String> md = fb.buildMetadata();
        assertEquals("42", md.get("int:>"));
        assertEquals("10000000000", md.get("long:>"));
        assertEquals("3.14", md.get("double:>"));
        assertEquals("2.5", md.get("float:>"));
    }

    @Test
    void buildMetadataReturnsNewInstanceEachTime() {
        FilterBuilder fb = FilterBuilder.create()
                .metaEq("key", "value");

        Map<String, String> md1 = fb.buildMetadata();
        Map<String, String> md2 = fb.buildMetadata();

        assertNotSame(md1, md2);
        assertEquals(md1, md2);
    }

    @Test
    void buildMetricsReturnsNewInstanceEachTime() {
        FilterBuilder fb = FilterBuilder.create()
                .metricEq("key", "value");

        Map<String, String> mt1 = fb.buildMetrics();
        Map<String, String> mt2 = fb.buildMetrics();

        assertNotSame(mt1, mt2);
        assertEquals(mt1, mt2);
    }

    @Test
    void modifyingBuiltMapDoesNotAffectBuilder() {
        FilterBuilder fb = FilterBuilder.create()
                .metaEq("key", "value");

        Map<String, String> md = fb.buildMetadata();
        // Build returns unmodifiable map, but even if it didn't:
        assertThrows(UnsupportedOperationException.class, () -> md.put("new", "value"));

        // Building again should still return the original data
        Map<String, String> md2 = fb.buildMetadata();
        assertEquals(1, md2.size());
        assertEquals("value", md2.get("key:=="));
    }

    @Test
    void nullValueInMetadata() {
        FilterBuilder fb = FilterBuilder.create()
                .metaEq("key", null);

        Map<String, String> md = fb.buildMetadata();
        // Null should be stored as null string
        assertNull(md.get("key:=="));
    }

    @Test
    void emptyStringValue() {
        FilterBuilder fb = FilterBuilder.create()
                .metaEq("key", "");

        Map<String, String> md = fb.buildMetadata();
        assertEquals("", md.get("key:=="));
    }

    @Test
    void specialCharactersInKeysAndValues() {
        FilterBuilder fb = FilterBuilder.create()
                .metaEq("key-with-dash", "value-with-dash")
                .metaEq("key_with_underscore", "value_with_underscore")
                .metaEq("key.with.dots", "value.with.dots")
                .metaEq("key:colon", "value:colon");

        Map<String, String> md = fb.buildMetadata();
        assertEquals("value-with-dash", md.get("key-with-dash:=="));
        assertEquals("value_with_underscore", md.get("key_with_underscore:=="));
        assertEquals("value.with.dots", md.get("key.with.dots:=="));
        assertEquals("value:colon", md.get("key:colon:=="));
    }

    @Test
    void veryLargeNumericValues() {
        FilterBuilder fb = FilterBuilder.create()
                .metaGt("big", Long.MAX_VALUE)
                .metaLt("small", Long.MIN_VALUE);

        Map<String, String> md = fb.buildMetadata();
        assertEquals(String.valueOf(Long.MAX_VALUE), md.get("big:>"));
        assertEquals(String.valueOf(Long.MIN_VALUE), md.get("small:<"));
    }

    @Test
    void negativeNumericValues() {
        FilterBuilder fb = FilterBuilder.create()
                .metaGt("temp", -10)
                .metaLt("depth", -100);

        Map<String, String> md = fb.buildMetadata();
        assertEquals("-10", md.get("temp:>"));
        assertEquals("-100", md.get("depth:<"));
    }

    @Test
    void zeroNumericValue() {
        FilterBuilder fb = FilterBuilder.create()
                .metaGt("count", 0);

        Map<String, String> md = fb.buildMetadata();
        assertEquals("0", md.get("count:>"));
    }
}
