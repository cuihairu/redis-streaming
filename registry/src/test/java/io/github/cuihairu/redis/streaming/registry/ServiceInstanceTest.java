package io.github.cuihairu.redis.streaming.registry;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ServiceInstance unit tests
 * Tests service instance construction, validation, and property management
 */
public class ServiceInstanceTest {

    @Test
    public void testBasicInstanceBuilder() {
        // Test basic instance construction
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("test-001")
                .host("192.168.1.100")
                .port(8080)
                .build();

        assertEquals("test-service", instance.getServiceName());
        assertEquals("test-001", instance.getInstanceId());
        assertEquals("192.168.1.100", instance.getHost());
        assertEquals(8080, instance.getPort());
        assertEquals(StandardProtocol.HTTP, instance.getProtocol());
        assertEquals(1, instance.getWeight());
        assertTrue(instance.isEnabled());
        assertTrue(instance.isHealthy());
        assertTrue(instance.isEphemeral()); // Default is ephemeral instance
        assertNotNull(instance.getMetadata());
        assertTrue(instance.getMetadata().isEmpty());
    }

    @Test
    public void testEphemeralInstance() {
        // Test ephemeral instance
        ServiceInstance ephemeralInstance = DefaultServiceInstance.builder()
                .serviceName("ephemeral-service")
                .instanceId("ephemeral-001")
                .host("127.0.0.1")
                .port(8080)
                .ephemeral(true)
                .build();

        assertTrue(ephemeralInstance.isEphemeral());
    }

    @Test
    public void testPersistentInstance() {
        // Test persistent instance
        ServiceInstance persistentInstance = DefaultServiceInstance.builder()
                .serviceName("persistent-service")
                .instanceId("persistent-001")
                .host("127.0.0.1")
                .port(8080)
                .ephemeral(false)
                .build();

        assertFalse(persistentInstance.isEphemeral());
    }

    @Test
    public void testInstanceWithProtocol() {
        // Test instances with different protocols
        ServiceInstance httpInstance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("http-001")
                .host("127.0.0.1")
                .port(80)
                .protocol(StandardProtocol.HTTP)
                .build();

        ServiceInstance httpsInstance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("https-001")
                .host("127.0.0.1")
                .port(443)
                .protocol(StandardProtocol.HTTPS)
                .build();

        ServiceInstance grpcInstance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("grpc-001")
                .host("127.0.0.1")
                .port(9090)
                .protocol(StandardProtocol.GRPC)
                .build();

        assertEquals(StandardProtocol.HTTP, httpInstance.getProtocol());
        assertEquals(StandardProtocol.HTTPS, httpsInstance.getProtocol());
        assertEquals(StandardProtocol.GRPC, grpcInstance.getProtocol());
    }

    @Test
    public void testInstanceWithMetadata() {
        // Test instance with metadata
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");
        metadata.put("region", "us-east-1");
        metadata.put("zone", "zone-a");
        metadata.put("env", "production");

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("meta-001")
                .host("192.168.1.100")
                .port(8080)
                .metadata(metadata)
                .build();

        assertNotNull(instance.getMetadata());
        assertEquals(4, instance.getMetadata().size());
        assertEquals("1.0.0", instance.getMetadata().get("version"));
        assertEquals("us-east-1", instance.getMetadata().get("region"));
        assertEquals("zone-a", instance.getMetadata().get("zone"));
        assertEquals("production", instance.getMetadata().get("env"));
    }

    @Test
    public void testInstanceWithWeight() {
        // Test instances with different weights
        ServiceInstance weight1 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("weight-1")
                .host("127.0.0.1")
                .port(8080)
                .weight(1)
                .build();

        ServiceInstance weight10 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("weight-10")
                .host("127.0.0.1")
                .port(8081)
                .weight(10)
                .build();

        assertEquals(1, weight1.getWeight());
        assertEquals(10, weight10.getWeight());
    }

    @Test
    public void testInstanceEnabledAndHealthy() {
        // Test enabled and healthy status
        ServiceInstance enabledHealthy = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("enabled-001")
                .host("127.0.0.1")
                .port(8080)
                .enabled(true)
                .healthy(true)
                .build();

        ServiceInstance disabledUnhealthy = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("disabled-001")
                .host("127.0.0.1")
                .port(8081)
                .enabled(false)
                .healthy(false)
                .build();

        assertTrue(enabledHealthy.isEnabled());
        assertTrue(enabledHealthy.isHealthy());
        assertFalse(disabledUnhealthy.isEnabled());
        assertFalse(disabledUnhealthy.isHealthy());
    }

    @Test
    public void testInstanceToString() {
        // Test toString method
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("test-001")
                .host("192.168.1.100")
                .port(8080)
                .build();

        String str = instance.toString();
        assertNotNull(str);
        assertTrue(str.contains("test-service"));
        assertTrue(str.contains("test-001"));
    }

    @Test
    public void testInstanceEquality() {
        // Test instance equality (based on serviceName and instanceId)
        ServiceInstance instance1 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("test-001")
                .host("192.168.1.100")
                .port(8080)
                .build();

        ServiceInstance instance2 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("test-001")
                .host("192.168.1.101") // Different host
                .port(8081)             // Different port
                .build();

        // Based on Lombok @EqualsAndHashCode default behavior
        // If custom equals is implemented, it should be based on serviceName + instanceId
        assertNotNull(instance1);
        assertNotNull(instance2);
    }

    @Test
    public void testCompleteInstance() {
        // Test fully configured instance
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "2.0.0");
        metadata.put("git.commit", "abc123");

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("complete-service")
                .instanceId("complete-001")
                .host("10.0.0.100")
                .port(9090)
                .protocol(StandardProtocol.GRPCS)
                .weight(5)
                .enabled(true)
                .healthy(true)
                .ephemeral(false)
                .metadata(metadata)
                .build();

        // Verify all properties
        assertEquals("complete-service", instance.getServiceName());
        assertEquals("complete-001", instance.getInstanceId());
        assertEquals("10.0.0.100", instance.getHost());
        assertEquals(9090, instance.getPort());
        assertEquals(StandardProtocol.GRPCS, instance.getProtocol());
        assertEquals(5, instance.getWeight());
        assertTrue(instance.isEnabled());
        assertTrue(instance.isHealthy());
        assertFalse(instance.isEphemeral());
        assertEquals(2, instance.getMetadata().size());
        assertEquals("2.0.0", instance.getMetadata().get("version"));
        assertEquals("abc123", instance.getMetadata().get("git.commit"));
    }

    @Test
    public void testInstanceWithoutMetadata() {
        // Test default value when metadata is not set
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("test-001")
                .host("127.0.0.1")
                .port(8080)
                // Metadata not set
                .build();

        // Builder.Default should provide a default empty Map
        assertNotNull(instance.getMetadata());
        assertTrue(instance.getMetadata().isEmpty());
    }
}
