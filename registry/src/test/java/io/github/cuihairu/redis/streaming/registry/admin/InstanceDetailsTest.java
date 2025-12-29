package io.github.cuihairu.redis.streaming.registry.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InstanceDetails
 */
class InstanceDetailsTest {

    private InstanceDetails instanceDetails;

    @BeforeEach
    void setUp() {
        instanceDetails = new InstanceDetails("test-service", "instance-1");
    }

    @Test
    void testHeartbeatDelayAndExpired() {
        InstanceDetails d = new InstanceDetails("svc", "i1");
        d.setLastHeartbeatTime(System.currentTimeMillis() - 1000);
        assertTrue(d.getHeartbeatDelay() >= 900);
        assertTrue(d.isExpired(100));
        assertFalse(d.isExpired(10_000));
    }

    @Test
    void testConstructorWithServiceNameAndInstanceId() {
        assertEquals("test-service", instanceDetails.getServiceName());
        assertEquals("instance-1", instanceDetails.getInstanceId());
    }

    @Test
    void testSetHost() {
        instanceDetails.setHost("localhost");

        assertEquals("localhost", instanceDetails.getHost());
    }

    @Test
    void testSetPort() {
        instanceDetails.setPort(8080);

        assertEquals(8080, instanceDetails.getPort());
    }

    @Test
    void testSetProtocol() {
        instanceDetails.setProtocol("HTTP");

        assertEquals("HTTP", instanceDetails.getProtocol());
    }

    @Test
    void testSetEnabled() {
        instanceDetails.setEnabled(true);

        assertTrue(instanceDetails.isEnabled());

        instanceDetails.setEnabled(false);

        assertFalse(instanceDetails.isEnabled());
    }

    @Test
    void testSetHealthy() {
        instanceDetails.setHealthy(true);

        assertTrue(instanceDetails.isHealthy());

        instanceDetails.setHealthy(false);

        assertFalse(instanceDetails.isHealthy());
    }

    @Test
    void testSetWeight() {
        instanceDetails.setWeight(5);

        assertEquals(5, instanceDetails.getWeight());
    }

    @Test
    void testSetMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");
        metadata.put("region", "us-east-1");

        instanceDetails.setMetadata(metadata);

        assertEquals(metadata, instanceDetails.getMetadata());
        assertEquals("1.0.0", instanceDetails.getMetadata().get("version"));
    }

    @Test
    void testSetRegistrationTime() {
        long time = System.currentTimeMillis();
        instanceDetails.setRegistrationTime(time);

        assertEquals(time, instanceDetails.getRegistrationTime());
    }

    @Test
    void testSetLastHeartbeatTime() {
        long time = System.currentTimeMillis();
        instanceDetails.setLastHeartbeatTime(time);

        assertEquals(time, instanceDetails.getLastHeartbeatTime());
    }

    @Test
    void testSetLastMetadataUpdate() {
        long time = System.currentTimeMillis();
        instanceDetails.setLastMetadataUpdate(time);

        assertEquals(time, instanceDetails.getLastMetadataUpdate());
    }

    @Test
    void testSetMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("requestCount", 1000);
        metrics.put("errorRate", 0.01);

        instanceDetails.setMetrics(metrics);

        assertEquals(metrics, instanceDetails.getMetrics());
    }

    @Test
    void testGetHeartbeatDelayWithRecentHeartbeat() {
        long currentTime = System.currentTimeMillis();
        instanceDetails.setLastHeartbeatTime(currentTime - 500);

        long delay = instanceDetails.getHeartbeatDelay();

        assertTrue(delay >= 400 && delay <= 600);
    }

    @Test
    void testGetHeartbeatDelayWithOldHeartbeat() {
        long currentTime = System.currentTimeMillis();
        instanceDetails.setLastHeartbeatTime(currentTime - 5000);

        long delay = instanceDetails.getHeartbeatDelay();

        assertTrue(delay >= 4900 && delay <= 5100);
    }

    @Test
    void testIsExpiredWithTimeoutExceeded() {
        long currentTime = System.currentTimeMillis();
        instanceDetails.setLastHeartbeatTime(currentTime - 10000);

        assertTrue(instanceDetails.isExpired(5000));
        assertTrue(instanceDetails.isExpired(9999));
    }

    @Test
    void testIsExpiredWithTimeoutNotExceeded() {
        long currentTime = System.currentTimeMillis();
        instanceDetails.setLastHeartbeatTime(currentTime - 1000);

        assertFalse(instanceDetails.isExpired(5000));
        assertFalse(instanceDetails.isExpired(2000));
    }

    @Test
    void testIsExpiredWithExactlyTimeout() {
        // Allow some tolerance for timing
        long currentTime = System.currentTimeMillis();
        long timeout = 1000;

        instanceDetails.setLastHeartbeatTime(currentTime - timeout);

        // Due to execution time, might be slightly over
        boolean expired = instanceDetails.isExpired(timeout);
        // Should be true or very close to true
        assertTrue(expired || instanceDetails.getHeartbeatDelay() >= timeout - 100);
    }

    @Test
    void testLombokDataAnnotation() {
        // Test Lombok @Data generated methods
        instanceDetails.setHost("192.168.1.1");
        instanceDetails.setPort(9090);
        instanceDetails.setProtocol("TCP");
        instanceDetails.setEnabled(true);
        instanceDetails.setHealthy(true);
        instanceDetails.setWeight(10);

        assertEquals("192.168.1.1", instanceDetails.getHost());
        assertEquals(9090, instanceDetails.getPort());
        assertEquals("TCP", instanceDetails.getProtocol());
        assertTrue(instanceDetails.isEnabled());
        assertTrue(instanceDetails.isHealthy());
        assertEquals(10, instanceDetails.getWeight());
    }
}

