package io.github.cuihairu.redis.streaming.registry.admin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ServiceDetailsTest {

    @Test
    public void testStatisticsAndRates() {
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
    public void testNullInstancesResetsStats() {
        ServiceDetails details = new ServiceDetails("svc");
        details.setInstances(null);
        assertEquals(0, details.getTotalInstances());
        assertEquals(0, details.getHealthyInstances());
        assertEquals(0, details.getEnabledInstances());
        assertEquals(0.0, details.getHealthyRate(), 0.0001);
        assertEquals(0.0, details.getEnabledRate(), 0.0001);
    }
}

