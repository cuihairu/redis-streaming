package io.github.cuihairu.redis.streaming.registry.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ServiceDetails
 */
class ServiceDetailsTest {

    private ServiceDetails serviceDetails;

    @BeforeEach
    void setUp() {
        serviceDetails = new ServiceDetails();
    }

    @Test
    void testStatisticsAndRates() {
        ServiceDetails details = new ServiceDetails("svc");
        assertEquals("svc", details.getServiceName());

        InstanceDetails i1 = new InstanceDetails("svc", "1");
        i1.setHealthy(true);
        i1.setEnabled(true);

        InstanceDetails i2 = new InstanceDetails("svc", "2");
        i2.setHealthy(false);
        i2.setEnabled(true);

        InstanceDetails i3 = new InstanceDetails("svc", "3");
        i3.setHealthy(true);
        i3.setEnabled(false);

        details.setInstances(List.of(i1, i2, i3));

        assertEquals(3, details.getTotalInstances());
        assertEquals(2, details.getHealthyInstances());
        assertEquals(2, details.getEnabledInstances());
        assertEquals(2.0 / 3.0 * 100.0, details.getHealthyRate(), 0.0001);
        assertEquals(2.0 / 3.0 * 100.0, details.getEnabledRate(), 0.0001);

        String str = details.toString();
        assertTrue(str.contains("ServiceDetails"));
        assertTrue(str.contains("healthyRate"));
    }

    @Test
    void testNullInstancesResetsStats() {
        ServiceDetails details = new ServiceDetails("svc");
        details.setInstances(null);
        assertEquals(0, details.getTotalInstances());
        assertEquals(0, details.getHealthyInstances());
        assertEquals(0, details.getEnabledInstances());
        assertEquals(0.0, details.getHealthyRate(), 0.0001);
        assertEquals(0.0, details.getEnabledRate(), 0.0001);
    }

    @Test
    void testDefaultConstructor() {
        ServiceDetails details = new ServiceDetails();

        assertNull(details.getServiceName());
        assertEquals(0, details.getTotalInstances());
        assertEquals(0, details.getHealthyInstances());
        assertEquals(0, details.getEnabledInstances());
        assertNull(details.getInstances());
        assertNull(details.getAggregatedMetrics());
    }

    @Test
    void testConstructorWithServiceName() {
        ServiceDetails details = new ServiceDetails("test-service");

        assertEquals("test-service", details.getServiceName());
        assertTrue(details.getLastUpdateTime() > 0);
    }

    @Test
    void testSetServiceName() {
        serviceDetails.setServiceName("my-service");

        assertEquals("my-service", serviceDetails.getServiceName());
    }

    @Test
    void testSetTotalInstances() {
        serviceDetails.setTotalInstances(5);

        assertEquals(5, serviceDetails.getTotalInstances());
    }

    @Test
    void testSetHealthyInstances() {
        serviceDetails.setHealthyInstances(3);

        assertEquals(3, serviceDetails.getHealthyInstances());
    }

    @Test
    void testSetEnabledInstances() {
        serviceDetails.setEnabledInstances(4);

        assertEquals(4, serviceDetails.getEnabledInstances());
    }

    @Test
    void testSetInstancesWithEmptyList() {
        serviceDetails.setInstances(Collections.emptyList());

        assertEquals(0, serviceDetails.getTotalInstances());
        assertEquals(0, serviceDetails.getHealthyInstances());
        assertEquals(0, serviceDetails.getEnabledInstances());
    }

    @Test
    void testSetAggregatedMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("avgHeartbeatDelay", 1000L);
        metrics.put("totalRequests", 1000);

        serviceDetails.setAggregatedMetrics(metrics);

        assertEquals(metrics, serviceDetails.getAggregatedMetrics());
    }

    @Test
    void testSetLastUpdateTime() {
        long time = System.currentTimeMillis();
        serviceDetails.setLastUpdateTime(time);

        assertEquals(time, serviceDetails.getLastUpdateTime());
    }

    @Test
    void testGetHealthyRateWithZeroInstances() {
        double rate = serviceDetails.getHealthyRate();

        assertEquals(0.0, rate);
    }

    @Test
    void testGetHealthyRateWithAllHealthy() {
        List<InstanceDetails> instances = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            InstanceDetails instance = new InstanceDetails("service", "instance" + i);
            instance.setHealthy(true);
            instances.add(instance);
        }

        serviceDetails.setInstances(instances);

        assertEquals(100.0, serviceDetails.getHealthyRate());
    }

    @Test
    void testGetEnabledRateWithZeroInstances() {
        double rate = serviceDetails.getEnabledRate();

        assertEquals(0.0, rate);
    }

    @Test
    void testGetEnabledRateWithAllEnabled() {
        List<InstanceDetails> instances = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            InstanceDetails instance = new InstanceDetails("service", "instance" + i);
            instance.setEnabled(true);
            instances.add(instance);
        }

        serviceDetails.setInstances(instances);

        assertEquals(100.0, serviceDetails.getEnabledRate());
    }
}

