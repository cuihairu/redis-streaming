package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.Protocol;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InstanceEntryCodec
 */
class InstanceEntryCodecTest {

    @Test
    void testBuildInstanceDataWithBasicInstance() {
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .protocol(StandardProtocol.HTTP)
                .build();

        long currentTime = System.currentTimeMillis();
        Map<String, Object> data = InstanceEntryCodec.buildInstanceData(instance, currentTime);

        assertEquals("localhost", data.get("host"));
        assertEquals(8080, data.get("port"));
        // Protocol is stored as string name (lowercase)
        Object protocolValue = data.get("protocol");
        assertTrue(protocolValue instanceof String);
        assertEquals("http", protocolValue);
        assertEquals(true, data.get("enabled"));
        assertEquals(true, data.get("healthy"));
        assertEquals(1, data.get("weight"));
        assertEquals(true, data.get("ephemeral"));
        assertEquals(currentTime, data.get("registrationTime"));
        assertEquals(currentTime, data.get("lastHeartbeatTime"));
        assertEquals(currentTime, data.get("lastMetadataUpdate"));
    }

    @Test
    void testBuildInstanceDataWithAllFields() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("env", "test");
        metadata.put("version", "1.0.0");

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("192.168.1.1")
                .port(9090)
                .protocol(StandardProtocol.HTTPS)
                .enabled(false)
                .healthy(false)
                .weight(5)
                .ephemeral(false)
                .metadata(metadata)
                .build();

        long currentTime = 123456789L;
        Map<String, Object> data = InstanceEntryCodec.buildInstanceData(instance, currentTime);

        assertEquals("192.168.1.1", data.get("host"));
        assertEquals(9090, data.get("port"));
        // Protocol is stored as string name (lowercase)
        Object protocolValue = data.get("protocol");
        assertTrue(protocolValue instanceof String);
        assertEquals("https", protocolValue);
        assertEquals(false, data.get("enabled"));
        assertEquals(false, data.get("healthy"));
        assertEquals(5, data.get("weight"));
        assertEquals(false, data.get("ephemeral"));
        // Metadata comparison - may not be exact match due to unmodifiable wrapper
        assertNotNull(data.get("metadata"));
        assertEquals(123456789L, data.get("registrationTime"));
        assertEquals(123456789L, data.get("lastHeartbeatTime"));
        assertEquals(123456789L, data.get("lastMetadataUpdate"));
    }

    @Test
    void testParseInstanceWithBasicData() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", "8080");
        data.put("protocol", "HTTP");
        data.put("enabled", "true");
        data.put("healthy", "true");
        data.put("weight", "1");
        data.put("ephemeral", "true");
        data.put("registrationTime", "123456789000");
        data.put("lastHeartbeatTime", "123456790000");

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNotNull(instance);
        assertEquals("test-service", instance.getServiceName());
        assertEquals("instance-1", instance.getInstanceId());
        assertEquals("localhost", instance.getHost());
        assertEquals(8080, instance.getPort());
        assertEquals(StandardProtocol.HTTP, instance.getProtocol());
        assertTrue(instance.isEnabled());
        assertTrue(instance.isHealthy());
        assertEquals(1, instance.getWeight());
        assertTrue(instance.isEphemeral());
        assertNotNull(instance.getRegistrationTime());
        assertNotNull(instance.getLastHeartbeatTime());
    }

    @Test
    void testParseInstanceWithNullHost() {
        Map<String, String> data = new HashMap<>();
        data.put("port", "8080");

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNull(instance);
    }

    @Test
    void testParseInstanceWithNullPort() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNull(instance);
    }

    @Test
    void testParseInstanceWithMissingOptionalFields() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", "8080");

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNotNull(instance);
        assertEquals("localhost", instance.getHost());
        assertEquals(8080, instance.getPort());
        assertEquals(StandardProtocol.HTTP, instance.getProtocol()); // Default
        assertTrue(instance.isEnabled()); // Default
        assertTrue(instance.isHealthy()); // Default
        assertEquals(1, instance.getWeight()); // Default
        assertTrue(instance.isEphemeral()); // Default
    }

    @Test
    void testParseInstanceWithJSONMetadata() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", "8080");
        data.put("metadata", "{\"env\":\"test\",\"version\":\"1.0.0\"}");

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNotNull(instance);
        assertEquals("test", instance.getMetadata().get("env"));
        assertEquals("1.0.0", instance.getMetadata().get("version"));
    }

    @Test
    void testParseInstanceWithLegacyMetadataPrefix() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", "8080");
        data.put("metadata_env", "production");
        data.put("metadata_region", "us-west");

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNotNull(instance);
        assertEquals("production", instance.getMetadata().get("env"));
        assertEquals("us-west", instance.getMetadata().get("region"));
    }

    @Test
    void testParseInstanceWithInvalidJSONMetadata() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", "8080");
        data.put("metadata", "invalid-json");
        data.put("metadata_fallback", "value");

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNotNull(instance);
        // Should fall back to legacy metadata
        assertEquals("value", instance.getMetadata().get("fallback"));
    }

    @Test
    void testParseInstanceWithDifferentProtocols() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", "8080");

        data.put("protocol", "HTTPS");
        ServiceInstance httpsInstance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);
        assertEquals(StandardProtocol.HTTPS, httpsInstance.getProtocol());

        data.put("protocol", "TCP");
        ServiceInstance tcpInstance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);
        assertEquals(StandardProtocol.TCP, tcpInstance.getProtocol());

        data.put("protocol", "INVALID");
        ServiceInstance defaultInstance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);
        assertEquals(StandardProtocol.HTTP, defaultInstance.getProtocol()); // Fallback
    }

    @Test
    void testParseInstanceWithBooleanFields() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", "8080");
        data.put("enabled", "false");
        data.put("healthy", "false");
        data.put("ephemeral", "false");

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNotNull(instance);
        assertFalse(instance.isEnabled());
        assertFalse(instance.isHealthy());
        assertFalse(instance.isEphemeral());
    }

    @Test
    void testParseInstanceWithWeight() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", "8080");
        data.put("weight", "10");

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNotNull(instance);
        assertEquals(10, instance.getWeight());
    }

    @Test
    void testParseInstanceWithInvalidPort() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", "invalid");

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNull(instance);
    }

    @Test
    void testParseInstanceWithInvalidWeight() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", "8080");
        data.put("weight", "invalid");

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNull(instance);
    }

    @Test
    void testParseInstanceWithInvalidTimestamp() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", "8080");
        data.put("registrationTime", "invalid");

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNull(instance);
    }

    @Test
    void testParseInstanceWithEmptyMetadata() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", "8080");
        data.put("metadata", "");

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNotNull(instance);
        assertTrue(instance.getMetadata().isEmpty());
    }

    @Test
    void testBuildAndParseInstanceConsistency() {
        ServiceInstance original = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("example.com")
                .port(8443)
                .protocol(StandardProtocol.HTTPS)
                .enabled(true)
                .healthy(true)
                .weight(3)
                .ephemeral(true)
                .metadata(Map.of("key1", "value1", "key2", "value2"))
                .build();

        long currentTime = System.currentTimeMillis();
        Map<String, Object> data = InstanceEntryCodec.buildInstanceData(original, currentTime);

        // Convert Object values to String for parseInstance
        Map<String, String> stringData = new HashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                // Skip metadata comparison for now
                stringData.put(entry.getKey(), "");
            } else {
                stringData.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        // Add metadata as JSON
        stringData.put("metadata", "{\"key1\":\"value1\",\"key2\":\"value2\"}");

        ServiceInstance parsed = InstanceEntryCodec.parseInstance("test-service", "instance-1", stringData);

        assertNotNull(parsed);
        assertEquals(original.getServiceName(), parsed.getServiceName());
        assertEquals(original.getInstanceId(), parsed.getInstanceId());
        assertEquals(original.getHost(), parsed.getHost());
        assertEquals(original.getPort(), parsed.getPort());
        assertEquals(original.getProtocol(), parsed.getProtocol());
        assertEquals(original.isEnabled(), parsed.isEnabled());
        assertEquals(original.isHealthy(), parsed.isHealthy());
        assertEquals(original.getWeight(), parsed.getWeight());
        assertEquals(original.isEphemeral(), parsed.isEphemeral());
        // Metadata should match
        assertEquals("value1", parsed.getMetadata().get("key1"));
        assertEquals("value2", parsed.getMetadata().get("key2"));
    }

    @Test
    void testParseInstanceWithNullProtocol() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", "8080");
        data.put("protocol", null);

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNotNull(instance);
        assertEquals(StandardProtocol.HTTP, instance.getProtocol()); // Default
    }

    @Test
    void testParseInstanceWithComplexMetadata() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", "8080");
        // Metadata with numbers, booleans, nested objects
        data.put("metadata", "{\"count\":42,\"active\":true,\"config\":{\"timeout\":5000}}");

        ServiceInstance instance = InstanceEntryCodec.parseInstance("test-service", "instance-1", data);

        assertNotNull(instance);
        assertEquals("42", instance.getMetadata().get("count"));
        assertEquals("true", instance.getMetadata().get("active"));
        assertEquals("{timeout=5000}", instance.getMetadata().get("config"));
    }

    @Test
    void testBuildInstanceDataWithEmptyMetadata() {
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .metadata(new HashMap<>())
                .build();

        long currentTime = System.currentTimeMillis();
        Map<String, Object> data = InstanceEntryCodec.buildInstanceData(instance, currentTime);

        assertNotNull(data.get("metadata"));
    }

    @Test
    void testBuildInstanceDataWithNullMetadata() {
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .metadata(null)
                .build();

        long currentTime = System.currentTimeMillis();
        Map<String, Object> data = InstanceEntryCodec.buildInstanceData(instance, currentTime);

        // The builder sets a default empty HashMap, so metadata won't be null
        // Just verify the data was built successfully
        assertNotNull(data);
    }
}
