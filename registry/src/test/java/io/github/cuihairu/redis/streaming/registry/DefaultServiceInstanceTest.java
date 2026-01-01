package io.github.cuihairu.redis.streaming.registry;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DefaultServiceInstance
 */
class DefaultServiceInstanceTest {

    @Test
    void testNoArgsConstructor() {
        // Given & When
        DefaultServiceInstance instance = new DefaultServiceInstance();

        // Then
        assertNotNull(instance);
        assertNull(instance.getServiceName());
        assertNull(instance.getInstanceId());
        assertNull(instance.getHost());
        // getPort() returns 80 (default HTTP port) since port field is 0
        assertEquals(80, instance.getPort());
        // protocol field is null, but getProtocol() returns default HTTP
        assertEquals(StandardProtocol.HTTP, instance.getProtocol());
        assertTrue(instance.isEnabled());
        assertTrue(instance.isHealthy());
        assertEquals(1, instance.getWeight());
        assertNotNull(instance.getMetadata());
        assertTrue(instance.getMetadata().isEmpty());
        assertNull(instance.getLastHeartbeatTime());
        assertNotNull(instance.getRegistrationTime());
        assertTrue(instance.isEphemeral());
    }

    @Test
    void testAllArgsConstructor() {
        // Given
        String serviceName = "test-service";
        String instanceId = "instance-1";
        String host = "localhost";
        int port = 8080;
        Protocol protocol = StandardProtocol.HTTPS;
        boolean enabled = true;
        boolean healthy = true;
        int weight = 5;
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "1.0");
        LocalDateTime lastHeartbeatTime = LocalDateTime.now();
        LocalDateTime registrationTime = LocalDateTime.now();
        boolean ephemeral = false;

        // When
        DefaultServiceInstance instance = new DefaultServiceInstance(
            serviceName, instanceId, host, port, protocol, enabled, healthy,
            weight, metadata, lastHeartbeatTime, registrationTime, ephemeral
        );

        // Then
        assertEquals(serviceName, instance.getServiceName());
        assertEquals(instanceId, instance.getInstanceId());
        assertEquals(host, instance.getHost());
        assertEquals(port, instance.getPort());
        assertEquals(protocol, instance.getProtocol());
        assertEquals(enabled, instance.isEnabled());
        assertEquals(healthy, instance.isHealthy());
        assertEquals(weight, instance.getWeight());
        assertEquals(metadata, instance.getMetadata());
        assertEquals(lastHeartbeatTime, instance.getLastHeartbeatTime());
        assertEquals(registrationTime, instance.getRegistrationTime());
        assertEquals(ephemeral, instance.isEphemeral());
    }

    @Test
    void testBuilder() {
        // Given
        Map<String, String> metadata = new HashMap<>();
        metadata.put("env", "test");

        // When
        DefaultServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("my-service")
            .instanceId("inst-123")
            .host("192.168.1.1")
            .port(9090)
            .protocol(StandardProtocol.TCP)
            .enabled(false)
            .healthy(false)
            .weight(10)
            .metadata(metadata)
            .lastHeartbeatTime(LocalDateTime.now())
            .registrationTime(LocalDateTime.now())
            .ephemeral(true)
            .build();

        // Then
        assertEquals("my-service", instance.getServiceName());
        assertEquals("inst-123", instance.getInstanceId());
        assertEquals("192.168.1.1", instance.getHost());
        assertEquals(9090, instance.getPort());
        assertEquals(StandardProtocol.TCP, instance.getProtocol());
        assertFalse(instance.isEnabled());
        assertFalse(instance.isHealthy());
        assertEquals(10, instance.getWeight());
        assertEquals(metadata, instance.getMetadata());
        assertTrue(instance.isEphemeral());
    }

    @Test
    void testBuilderWithDefaults() {
        // When
        DefaultServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("default-service")
            .instanceId("default-instance")
            .host("localhost")
            .port(8080)
            .build();

        // Then - should have default values
        assertEquals("default-service", instance.getServiceName());
        assertEquals("default-instance", instance.getInstanceId());
        assertEquals("localhost", instance.getHost());
        assertEquals(8080, instance.getPort());
        assertEquals(StandardProtocol.HTTP, instance.getProtocol());
        assertTrue(instance.isEnabled());
        assertTrue(instance.isHealthy());
        assertEquals(1, instance.getWeight());
        assertNotNull(instance.getMetadata());
        assertTrue(instance.getMetadata().isEmpty());
        assertNull(instance.getLastHeartbeatTime());
        assertNotNull(instance.getRegistrationTime());
        assertTrue(instance.isEphemeral());
    }

    @Test
    void testGetPortWithPositivePort() {
        // Given
        DefaultServiceInstance instance = DefaultServiceInstance.builder()
            .host("localhost")
            .port(8080)
            .protocol(StandardProtocol.HTTP)
            .build();

        // When
        int port = instance.getPort();

        // Then - should return the actual port
        assertEquals(8080, port);
    }

    @Test
    void testGetPortWithZeroPortAndHttpProtocol() {
        // Given
        DefaultServiceInstance instance = DefaultServiceInstance.builder()
            .host("localhost")
            .port(0)
            .protocol(StandardProtocol.HTTP)
            .build();

        // When
        int port = instance.getPort();

        // Then - should return default HTTP port
        assertEquals(80, port);
    }

    @Test
    void testGetPortWithZeroPortAndHttpsProtocol() {
        // Given
        DefaultServiceInstance instance = DefaultServiceInstance.builder()
            .host("localhost")
            .port(0)
            .protocol(StandardProtocol.HTTPS)
            .build();

        // When
        int port = instance.getPort();

        // Then - should return default HTTPS port
        assertEquals(443, port);
    }

    @Test
    void testGetPortWithZeroPortAndTcpProtocol() {
        // Given
        DefaultServiceInstance instance = DefaultServiceInstance.builder()
            .host("localhost")
            .port(0)
            .protocol(StandardProtocol.TCP)
            .build();

        // When
        int port = instance.getPort();

        // Then - TCP has no default port, should return 0
        assertEquals(0, port);
    }

    @Test
    void testGetPortWithNegativePort() {
        // Given
        DefaultServiceInstance instance = DefaultServiceInstance.builder()
            .host("localhost")
            .port(-100)
            .protocol(StandardProtocol.HTTP)
            .build();

        // When
        int port = instance.getPort();

        // Then - negative port is not > 0, so should use default
        assertEquals(80, port);
    }

    @Test
    void testGetProtocolWithNonNullProtocol() {
        // Given
        DefaultServiceInstance instance = DefaultServiceInstance.builder()
            .protocol(StandardProtocol.HTTPS)
            .build();

        // When
        Protocol protocol = instance.getProtocol();

        // Then
        assertEquals(StandardProtocol.HTTPS, protocol);
    }

    @Test
    void testGetProtocolWithNullProtocol() {
        // Given
        DefaultServiceInstance instance = new DefaultServiceInstance();
        instance.setProtocol(null);

        // When
        Protocol protocol = instance.getProtocol();

        // Then - should return default HTTP protocol
        assertEquals(StandardProtocol.HTTP, protocol);
    }

    @Test
    void testSettersAndGetters() {
        // Given
        DefaultServiceInstance instance = new DefaultServiceInstance();

        // When
        instance.setServiceName("new-service");
        instance.setInstanceId("new-instance");
        instance.setHost("example.com");
        instance.setPort(9999);
        instance.setProtocol(StandardProtocol.WS);
        instance.setEnabled(false);
        instance.setHealthy(false);
        instance.setWeight(20);
        instance.setLastHeartbeatTime(LocalDateTime.now());
        instance.setRegistrationTime(LocalDateTime.now());
        instance.setEphemeral(false);

        // Then
        assertEquals("new-service", instance.getServiceName());
        assertEquals("new-instance", instance.getInstanceId());
        assertEquals("example.com", instance.getHost());
        assertEquals(9999, instance.getPort());
        assertEquals(StandardProtocol.WS, instance.getProtocol());
        assertFalse(instance.isEnabled());
        assertFalse(instance.isHealthy());
        assertEquals(20, instance.getWeight());
        assertFalse(instance.isEphemeral());
    }

    @Test
    void testMetadataSetter() {
        // Given
        DefaultServiceInstance instance = new DefaultServiceInstance();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("key2", "value2");

        // When
        instance.setMetadata(metadata);

        // Then
        assertEquals(metadata, instance.getMetadata());
        assertEquals(2, instance.getMetadata().size());
    }

    @Test
    void testEmptyMetadata() {
        // Given
        DefaultServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("service")
            .instanceId("instance")
            .metadata(new HashMap<>())
            .build();

        // When
        Map<String, String> metadata = instance.getMetadata();

        // Then
        assertNotNull(metadata);
        assertTrue(metadata.isEmpty());
    }

    @Test
    void testNullMetadataWithBuilder() {
        // Given & When
        DefaultServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("service")
            .instanceId("instance")
            .metadata(null)
            .build();

        // Then - when metadata is set to null via builder, it becomes null
        // Note: @Builder.Default only applies when the field is not explicitly set
        assertNull(instance.getMetadata());
    }

    @Test
    void testServiceIdentityInterface() {
        // Given
        DefaultServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("my-service")
            .instanceId("my-instance")
            .build();

        // When
        ServiceIdentity identity = instance;

        // Then
        assertEquals("my-service", identity.getServiceName());
        assertEquals("my-instance", identity.getInstanceId());
    }

    @Test
    void testServiceInstanceInterface() {
        // Given
        DefaultServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("test-service")
            .instanceId("test-instance")
            .host("192.168.1.100")
            .port(8443)
            .protocol(StandardProtocol.HTTPS)
            .enabled(true)
            .healthy(true)
            .weight(5)
            .metadata(Map.of("zone", "us-east-1"))
            .build();

        // When
        ServiceInstance serviceInstance = instance;

        // Then
        assertEquals("test-service", serviceInstance.getServiceName());
        assertEquals("test-instance", serviceInstance.getInstanceId());
        assertEquals("192.168.1.100", serviceInstance.getHost());
        assertEquals(8443, serviceInstance.getPort());
        assertEquals(StandardProtocol.HTTPS, serviceInstance.getProtocol());
        assertTrue(serviceInstance.isEnabled());
        assertTrue(serviceInstance.isHealthy());
        assertEquals(5, serviceInstance.getWeight());
        assertEquals(Map.of("zone", "us-east-1"), serviceInstance.getMetadata());
    }

    @Test
    void testEqualsAndHashCode() {
        // Given
        DefaultServiceInstance instance1 = DefaultServiceInstance.builder()
            .serviceName("service")
            .instanceId("instance")
            .host("localhost")
            .port(8080)
            .build();

        DefaultServiceInstance instance3 = DefaultServiceInstance.builder()
            .serviceName("other-service")
            .instanceId("other-instance")
            .host("localhost")
            .port(8080)
            .build();

        // Then - each instance is unique due to registrationTime
        // Lombok @Data generates equals/hashCode based on ALL fields
        assertNotEquals(instance1, instance3);
    }

    @Test
    void testToString() {
        // Given
        DefaultServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("my-service")
            .instanceId("my-instance")
            .host("localhost")
            .port(8080)
            .build();

        // When
        String str = instance.toString();

        // Then - Lombok @Data should generate toString
        assertNotNull(str);
        assertTrue(str.contains("my-service") || str.contains("my-instance"));
    }

    @Test
    void testCanBeUsedWithLombokBuilder() {
        // Given & When - using builder pattern with partial fields
        DefaultServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("partial-service")
            .host("example.com")
            .port(443)
            .protocol(StandardProtocol.HTTPS)
            .build();

        // Then - unset fields should have defaults
        assertEquals("partial-service", instance.getServiceName());
        assertEquals("example.com", instance.getHost());
        assertEquals(443, instance.getPort());
        assertEquals(StandardProtocol.HTTPS, instance.getProtocol());
        assertNull(instance.getInstanceId());
        assertTrue(instance.isEnabled());
        assertTrue(instance.isHealthy());
        assertEquals(1, instance.getWeight());
    }

    @Test
    void testEphemeralInstance() {
        // Given & When
        DefaultServiceInstance ephemeral = DefaultServiceInstance.builder()
            .serviceName("ephemeral-service")
            .ephemeral(true)
            .build();

        DefaultServiceInstance permanent = DefaultServiceInstance.builder()
            .serviceName("permanent-service")
            .ephemeral(false)
            .build();

        // Then
        assertTrue(ephemeral.isEphemeral());
        assertFalse(permanent.isEphemeral());
    }

    @Test
    void testWithAllStandardProtocols() {
        // Test that instance works with all standard protocols
        assertEquals(80, DefaultServiceInstance.builder().port(0).protocol(StandardProtocol.HTTP).build().getPort());
        assertEquals(443, DefaultServiceInstance.builder().port(0).protocol(StandardProtocol.HTTPS).build().getPort());
        assertEquals(80, DefaultServiceInstance.builder().port(0).protocol(StandardProtocol.WS).build().getPort());
        assertEquals(443, DefaultServiceInstance.builder().port(0).protocol(StandardProtocol.WSS).build().getPort());
        assertEquals(0, DefaultServiceInstance.builder().port(0).protocol(StandardProtocol.TCP).build().getPort());
        assertEquals(0, DefaultServiceInstance.builder().port(0).protocol(StandardProtocol.UDP).build().getPort());
    }
}
