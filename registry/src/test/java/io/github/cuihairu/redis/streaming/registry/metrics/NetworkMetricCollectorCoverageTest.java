package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers NetworkMetricCollector Tomcat-MBean branches via locally registered Catalina MBeans,
 * and parseLong edge cases (including the NumberFormatException fallback) via reflection.
 */
class NetworkMetricCollectorCoverageTest {

    private final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    private final java.util.List<ObjectName> registered = new java.util.ArrayList<>();

    @AfterEach
    void unregisterMBeans() throws Exception {
        for (ObjectName on : registered) {
            if (mbs.isRegistered(on)) {
                mbs.unregisterMBean(on);
            }
        }
        registered.clear();
    }

    private ObjectName register(String name, DynamicMBean bean) throws Exception {
        ObjectName on = new ObjectName(name);
        mbs.registerMBean(bean, on);
        registered.add(on);
        return on;
    }

    private static DynamicMBean attrs(Map<String, Object> values) {
        return new MapMBean(values, false);
    }

    private static DynamicMBean throwing() {
        return new MapMBean(Map.of(), true);
    }

    private static final class MapMBean implements DynamicMBean {
        private final Map<String, Object> values;
        private final boolean throwOnGet;

        MapMBean(Map<String, Object> values, boolean throwOnGet) {
            this.values = new HashMap<>(values);
            this.throwOnGet = throwOnGet;
        }

        @Override
        public Object getAttribute(String attribute) throws javax.management.MBeanException {
            if (throwOnGet) {
                throw new javax.management.MBeanException(new IllegalStateException("boom"));
            }
            return values.get(attribute);
        }

        @Override
        public void setAttribute(Attribute attribute) {
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            return new AttributeList();
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) {
            return null;
        }

        @Override
        public javax.management.MBeanInfo getMBeanInfo() {
            return new javax.management.MBeanInfo(
                    MapMBean.class.getName(), "test",
                    new javax.management.MBeanAttributeInfo[0],
                    new javax.management.MBeanConstructorInfo[0],
                    new javax.management.MBeanOperationInfo[0],
                    new javax.management.MBeanNotificationInfo[0]);
        }
    }

    @Test
    void collectMetricAggregatesTomcatRequestAndThreadPoolMBeans() throws Exception {
        register("Catalina:type=GlobalRequestProcessor,name=\"http-nio-a\"",
                attrs(Map.of("requestCount", 5L, "errorCount", 2L)));
        register("Catalina:type=GlobalRequestProcessor,name=\"http-nio-b\"",
                attrs(Map.of("requestCount", 7L, "errorCount", 1L)));
        register("Catalina:type=ThreadPool,name=\"http-nio-a\"",
                attrs(Map.of("currentThreadsBusy", 3L)));

        Map<String, Object> out = (Map<String, Object>) new NetworkMetricCollector().collectMetric();

        assertEquals(12L, ((Number) out.get("requests")).longValue());
        assertEquals(12L, ((Number) out.get(MetricKeys.NETWORK_REQUESTS)).longValue());
        assertEquals(3L, ((Number) out.get("errors")).longValue());
        assertEquals(3L, ((Number) out.get(MetricKeys.NETWORK_ERRORS)).longValue());
        assertEquals(3L, ((Number) out.get("connections")).longValue());
        assertEquals(3L, ((Number) out.get(MetricKeys.NETWORK_CONNECTIONS)).longValue());
    }

    @Test
    void collectMetricSkipsNonNumericAttributesAndToleratesAttributeFailures() throws Exception {
        register("Catalina:type=GlobalRequestProcessor,name=\"np\"",
                attrs(Map.of("requestCount", "not-a-number", "errorCount", "oops")));
        register("Catalina:type=GlobalRequestProcessor,name=\"bad\"", throwing());
        register("Catalina:type=ThreadPool,name=\"np\"",
                attrs(Map.of("currentThreadsBusy", "busy")));
        register("Catalina:type=ThreadPool,name=\"bad\"", throwing());

        Map<String, Object> out = (Map<String, Object>) new NetworkMetricCollector().collectMetric();

        assertFalse(out.containsKey("requests"));
        assertFalse(out.containsKey("errors"));
        assertFalse(out.containsKey("connections"));
        assertTrue(out.containsKey("rxBytes") || !new java.io.File("/proc/net/dev").exists());
    }

    @Test
    void parseLongHandlesBadInput() throws Exception {
        Method parseLong = NetworkMetricCollector.class.getDeclaredMethod("parseLong", String.class);
        parseLong.setAccessible(true);
        assertEquals(42L, parseLong.invoke(null, "42"));
        assertEquals(0L, parseLong.invoke(null, "abc"));
        assertEquals(0L, parseLong.invoke(null, ""));
        assertEquals(0L, parseLong.invoke(null, (Object) null));
    }
}
