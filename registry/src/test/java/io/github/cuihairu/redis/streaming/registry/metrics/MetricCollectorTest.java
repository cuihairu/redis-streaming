package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricCollector interface
 */
class MetricCollectorTest {

    @Test
    void testInterfaceHasGetMetricTypeMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("getMetricType"));
    }

    @Test
    void testInterfaceHasCollectMetricMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("collectMetric"));
    }

    @Test
    void testInterfaceHasIsAvailableMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("isAvailable"));
    }

    @Test
    void testInterfaceHasGetCostMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("getCost"));
    }

    @Test
    void testSimpleImplementation() throws Exception {
        // Given
        MetricCollector collector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "test-metric";
            }

            @Override
            public Object collectMetric() {
                return "metric-value";
            }
        };

        // When & Then
        assertEquals("test-metric", collector.getMetricType());
        assertEquals("metric-value", collector.collectMetric());
    }

    @Test
    void testDefaultIsAvailableReturnsTrue() {
        // Given
        MetricCollector collector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "test";
            }

            @Override
            public Object collectMetric() {
                return null;
            }
        };

        // When & Then
        assertTrue(collector.isAvailable());
    }

    @Test
    void testOverrideIsAvailable() {
        // Given
        MetricCollector collector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "test";
            }

            @Override
            public Object collectMetric() {
                return null;
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };

        // When & Then
        assertFalse(collector.isAvailable());
    }

    @Test
    void testDefaultGetCostReturnsLow() {
        // Given
        MetricCollector collector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "test";
            }

            @Override
            public Object collectMetric() {
                return null;
            }
        };

        // When & Then
        assertEquals(CollectionCost.LOW, collector.getCost());
    }

    @Test
    void testOverrideGetCost() {
        // Given
        MetricCollector collector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "test";
            }

            @Override
            public Object collectMetric() {
                return null;
            }

            @Override
            public CollectionCost getCost() {
                return CollectionCost.HIGH;
            }
        };

        // When & Then
        assertEquals(CollectionCost.HIGH, collector.getCost());
    }

    @Test
    void testCollectMetricThrowsException() {
        // Given
        MetricCollector collector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "failing-metric";
            }

            @Override
            public Object collectMetric() throws Exception {
                throw new IllegalStateException("Collection failed");
            }
        };

        // When & Then
        assertThrows(IllegalStateException.class, collector::collectMetric);
    }

    @Test
    void testCollectMetricReturnsDifferentTypes() throws Exception {
        // Given - String collector
        MetricCollector stringCollector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "string";
            }

            @Override
            public Object collectMetric() {
                return "string-value";
            }
        };

        // Given - Number collector
        MetricCollector numberCollector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "number";
            }

            @Override
            public Object collectMetric() {
                return 42;
            }
        };

        // When & Then
        assertEquals("string-value", stringCollector.collectMetric());
        assertEquals(42, numberCollector.collectMetric());
    }

    @Test
    void testCollectMetricReturnsNull() throws Exception {
        // Given
        MetricCollector collector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "null-metric";
            }

            @Override
            public Object collectMetric() {
                return null;
            }
        };

        // When & Then
        assertNull(collector.collectMetric());
    }

    @Test
    void testMultipleCollectors() throws Exception {
        // Given
        MetricCollector collector1 = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "metric1";
            }

            @Override
            public Object collectMetric() {
                return "value1";
            }
        };

        MetricCollector collector2 = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "metric2";
            }

            @Override
            public Object collectMetric() {
                return "value2";
            }
        };

        // When & Then
        assertEquals("metric1", collector1.getMetricType());
        assertEquals("metric2", collector2.getMetricType());
        assertEquals("value1", collector1.collectMetric());
        assertEquals("value2", collector2.collectMetric());
    }

    @Test
    void testAllCostLevels() {
        // Test LOW cost (default)
        MetricCollector lowCostCollector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "low";
            }

            @Override
            public Object collectMetric() {
                return null;
            }
        };
        assertEquals(CollectionCost.LOW, lowCostCollector.getCost());

        // Test MEDIUM cost
        MetricCollector mediumCostCollector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "medium";
            }

            @Override
            public Object collectMetric() {
                return null;
            }

            @Override
            public CollectionCost getCost() {
                return CollectionCost.MEDIUM;
            }
        };
        assertEquals(CollectionCost.MEDIUM, mediumCostCollector.getCost());

        // Test HIGH cost
        MetricCollector highCostCollector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "high";
            }

            @Override
            public Object collectMetric() {
                return null;
            }

            @Override
            public CollectionCost getCost() {
                return CollectionCost.HIGH;
            }
        };
        assertEquals(CollectionCost.HIGH, highCostCollector.getCost());
    }

    @Test
    void testCollectorWithComplexMetric() throws Exception {
        // Given
        class ComplexMetric {
            private final String name;
            private final double value;
            private final long timestamp;

            ComplexMetric(String name, double value, long timestamp) {
                this.name = name;
                this.value = value;
                this.timestamp = timestamp;
            }
        }

        MetricCollector collector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "complex";
            }

            @Override
            public Object collectMetric() {
                return new ComplexMetric("cpu.usage", 75.5, System.currentTimeMillis());
            }
        };

        // When
        Object result = collector.collectMetric();

        // Then
        assertNotNull(result);
        assertTrue(result instanceof ComplexMetric);
        ComplexMetric metric = (ComplexMetric) result;
        assertEquals("cpu.usage", metric.name);
        assertEquals(75.5, metric.value, 0.001);
    }

    @Test
    void testCollectorWithConditionalAvailability() {
        // Given - collector that checks system property
        MetricCollector collector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "conditional";
            }

            @Override
            public Object collectMetric() {
                return "value";
            }

            @Override
            public boolean isAvailable() {
                return System.getProperty("java.version") != null;
            }
        };

        // When & Then - java.version should always be available
        assertTrue(collector.isAvailable());
    }

    @Test
    void testLambdaImplementation() throws Exception {
        // Given - lambda won't work for multi-method interface, so using anonymous class
        MetricCollector collector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "lambda-style";
            }

            @Override
            public Object collectMetric() {
                return 100;
            }
        };

        // When & Then
        assertEquals("lambda-style", collector.getMetricType());
        assertEquals(100, collector.collectMetric());
    }
}
