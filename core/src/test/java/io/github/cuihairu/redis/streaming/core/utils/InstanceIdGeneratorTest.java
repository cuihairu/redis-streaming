package io.github.cuihairu.redis.streaming.core.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InstanceIdGenerator utility class
 */
public class InstanceIdGeneratorTest {

    @Test
    public void testGenerateInstanceIdWithHost() {
        // Test instance ID generation with host address
        String serviceName = "user-service";
        String host = "192.168.1.100";
        int port = 8080;
        
        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, host, port);
        assertEquals("user-service-192.168.1.100:8080", instanceId);
    }

    @Test
    public void testGenerateInstanceIdWithLocalHost() {
        // Test instance ID generation using local hostname
        String serviceName = "order-service";
        int port = 9090;
        
        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, port);
        String hostname = SystemUtils.getLocalHostname();
        assertEquals(serviceName + "-" + hostname + ":" + port, instanceId);
    }

    @Test
    public void testGenerateLocalInstanceId() {
        // Test generating local instance ID
        String serviceName = "payment-service";
        int port = 8888;

        String instanceId = InstanceIdGenerator.generateLocalInstanceId(serviceName, port);
        String hostname = SystemUtils.getLocalHostname();
        assertEquals(hostname + ":" + port, instanceId);
    }

    @Test
    public void testGenerateInstanceIdWithIPv6Address() {
        // Test instance ID generation with IPv6 address
        String serviceName = "api-gateway";
        String host = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";
        int port = 8080;

        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, host, port);
        assertEquals("api-gateway-2001:0db8:85a3:0000:0000:8a2e:0370:7334:8080", instanceId);
    }

    @Test
    public void testGenerateInstanceIdWithLocalhost() {
        // Test instance ID generation using localhost
        String serviceName = "local-service";
        String host = "localhost";
        int port = 3000;

        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, host, port);
        assertEquals("local-service-localhost:3000", instanceId);
    }

    @Test
    public void testGenerateInstanceIdWithEmptyServiceName() {
        // Test with empty service name
        String serviceName = "";
        String host = "192.168.1.1";
        int port = 8080;

        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, host, port);
        assertEquals("-192.168.1.1:8080", instanceId);
    }

    @Test
    public void testGenerateInstanceIdWithSpecialCharacters() {
        // Test with special characters in service name
        String serviceName = "my_service.v2";
        String host = "example.com";
        int port = 443;

        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, host, port);
        assertEquals("my_service.v2-example.com:443", instanceId);
    }

    @Test
    public void testGenerateInstanceIdWithZeroPort() {
        // Test with port 0
        String serviceName = "test-service";
        String host = "127.0.0.1";
        int port = 0;

        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, host, port);
        assertEquals("test-service-127.0.0.1:0", instanceId);
    }

    @Test
    public void testGenerateInstanceIdWithMaxPort() {
        // Test with maximum port number
        String serviceName = "test-service";
        String host = "10.0.0.1";
        int port = 65535;

        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, host, port);
        assertEquals("test-service-10.0.0.1:65535", instanceId);
    }

    @Test
    public void testGenerateInstanceIdFormatConsistency() {
        // Test format consistency across different parameter combinations
        String serviceName = "my-service";
        String host1 = "192.168.1.1";
        String host2 = "192.168.1.2";
        int port = 8080;

        String id1 = InstanceIdGenerator.generateInstanceId(serviceName, host1, port);
        String id2 = InstanceIdGenerator.generateInstanceId(serviceName, host2, port);

        // Verify consistent format: serviceName-host:port
        assertTrue(id1.matches("^[\\w.-]+-[\\w.:]+:\\d+$"));
        assertTrue(id2.matches("^[\\w.-]+-[\\w.:]+:\\d+$"));
        assertNotEquals(id1, id2);
    }

    @Test
    public void testGenerateInstanceIdUniqueness() {
        // Test different parameters generate different instance IDs
        String serviceName = "unique-service";

        String id1 = InstanceIdGenerator.generateInstanceId(serviceName, "host1", 8080);
        String id2 = InstanceIdGenerator.generateInstanceId(serviceName, "host1", 8081);
        String id3 = InstanceIdGenerator.generateInstanceId(serviceName, "host2", 8080);

        assertNotEquals(id1, id2);
        assertNotEquals(id1, id3);
        assertNotEquals(id2, id3);
    }

    @Test
    public void testGenerateLocalInstanceIdWithoutServiceNamePrefix() {
        // Test generateLocalInstanceId does not include service name prefix
        String serviceName = "ignored-service";
        int port = 9999;

        String instanceId = InstanceIdGenerator.generateLocalInstanceId(serviceName, port);
        String hostname = SystemUtils.getLocalHostname();

        // Verify format: hostname:port (without service name)
        assertFalse(instanceId.startsWith(serviceName + "-"));
        assertEquals(hostname + ":" + port, instanceId);
    }
}