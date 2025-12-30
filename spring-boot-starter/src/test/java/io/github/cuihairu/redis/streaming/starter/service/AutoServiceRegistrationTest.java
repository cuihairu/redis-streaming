package io.github.cuihairu.redis.streaming.starter.service;

import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.Protocol;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AutoServiceRegistration
 * Note: This class is tightly coupled with Spring Framework, so unit tests are limited.
 * Full coverage requires integration tests with actual Spring context.
 */
class AutoServiceRegistrationTest {

    @Mock
    private NamingService mockNamingService;

    private RedisStreamingProperties properties;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = createDefaultProperties();
    }

    private RedisStreamingProperties createDefaultProperties() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setEnabled(true);
        props.getRegistry().setAutoRegister(true);
        props.getRegistry().setHeartbeatInterval(30);
        props.getRegistry().getInstance().setServiceName("test-service");
        props.getRegistry().getInstance().setPort(8080);
        props.getRegistry().getInstance().setProtocol("http");
        props.getRegistry().getInstance().setEphemeral(true);
        props.getRegistry().getInstance().setEnabled(true);
        props.getRegistry().getInstance().setWeight(1);
        props.getRegistry().getInstance().setMetadata(new HashMap<>());
        return props;
    }

    // ===== Constructor Tests =====

    @Test
    void testConstructorWithValidParameters() {
        // AutoServiceRegistration requires Spring context, but we can test basic instantiation
        assertDoesNotThrow(() -> {
            AutoServiceRegistration registration = new AutoServiceRegistration();
            assertNotNull(registration);
        });
    }

    // ===== Properties Tests =====

    @Test
    void testPropertiesWithEnabledRegistry() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setEnabled(true);
        assertTrue(props.getRegistry().isEnabled());
    }

    @Test
    void testPropertiesWithDisabledRegistry() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setEnabled(false);
        assertFalse(props.getRegistry().isEnabled());
    }

    @Test
    void testPropertiesWithAutoRegister() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setAutoRegister(true);
        assertTrue(props.getRegistry().isAutoRegister());
    }

    @Test
    void testPropertiesWithoutAutoRegister() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setAutoRegister(false);
        assertFalse(props.getRegistry().isAutoRegister());
    }

    @Test
    void testPropertiesWithHeartbeatInterval() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setHeartbeatInterval(60);
        assertEquals(60, props.getRegistry().getHeartbeatInterval());
    }

    @Test
    void testPropertiesWithZeroHeartbeatInterval() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setHeartbeatInterval(0);
        assertEquals(0, props.getRegistry().getHeartbeatInterval());
    }

    @Test
    void testPropertiesWithNegativeHeartbeatInterval() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setHeartbeatInterval(-1);
        assertEquals(-1, props.getRegistry().getHeartbeatInterval());
    }

    // ===== Instance Properties Tests =====

    @Test
    void testInstancePropertiesWithServiceName() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setServiceName("my-service");
        assertEquals("my-service", props.getRegistry().getInstance().getServiceName());
    }

    @Test
    void testInstancePropertiesWithNullServiceName() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setServiceName(null);
        assertNull(props.getRegistry().getInstance().getServiceName());
    }

    @Test
    void testInstancePropertiesWithEmptyServiceName() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setServiceName("");
        assertEquals("", props.getRegistry().getInstance().getServiceName());
    }

    @Test
    void testInstancePropertiesWithInstanceId() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setInstanceId("instance-1");
        assertEquals("instance-1", props.getRegistry().getInstance().getInstanceId());
    }

    @Test
    void testInstancePropertiesWithNullInstanceId() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setInstanceId(null);
        assertNull(props.getRegistry().getInstance().getInstanceId());
    }

    @Test
    void testInstancePropertiesWithHost() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setHost("192.168.1.100");
        assertEquals("192.168.1.100", props.getRegistry().getInstance().getHost());
    }

    @Test
    void testInstancePropertiesWithNullHost() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setHost(null);
        assertNull(props.getRegistry().getInstance().getHost());
    }

    @Test
    void testInstancePropertiesWithPort() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setPort(9090);
        assertEquals(9090, props.getRegistry().getInstance().getPort());
    }

    @Test
    void testInstancePropertiesWithZeroPort() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setPort(0);
        assertEquals(0, props.getRegistry().getInstance().getPort());
    }

    @Test
    void testInstancePropertiesWithNegativePort() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setPort(-1);
        assertEquals(-1, props.getRegistry().getInstance().getPort());
    }

    @Test
    void testInstancePropertiesWithProtocolHttp() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setProtocol("http");
        assertEquals("http", props.getRegistry().getInstance().getProtocol());
    }

    @Test
    void testInstancePropertiesWithProtocolHttps() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setProtocol("https");
        assertEquals("https", props.getRegistry().getInstance().getProtocol());
    }

    @Test
    void testInstancePropertiesWithProtocolTcp() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setProtocol("tcp");
        assertEquals("tcp", props.getRegistry().getInstance().getProtocol());
    }

    @Test
    void testInstancePropertiesWithInvalidProtocol() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setProtocol("invalid");
        assertEquals("invalid", props.getRegistry().getInstance().getProtocol());
    }

    @Test
    void testInstancePropertiesWithNullProtocol() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setProtocol(null);
        assertNull(props.getRegistry().getInstance().getProtocol());
    }

    @Test
    void testInstancePropertiesWithEphemeralTrue() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setEphemeral(true);
        assertTrue(props.getRegistry().getInstance().getEphemeral());
    }

    @Test
    void testInstancePropertiesWithEphemeralFalse() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setEphemeral(false);
        assertFalse(props.getRegistry().getInstance().getEphemeral());
    }

    @Test
    void testInstancePropertiesWithNullEphemeral() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setEphemeral(null);
        assertNull(props.getRegistry().getInstance().getEphemeral());
    }

    @Test
    void testInstancePropertiesWithEnabledTrue() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setEnabled(true);
        assertTrue(props.getRegistry().getInstance().isEnabled());
    }

    @Test
    void testInstancePropertiesWithEnabledFalse() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setEnabled(false);
        assertFalse(props.getRegistry().getInstance().isEnabled());
    }

    @Test
    void testInstancePropertiesWithWeight() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setWeight(10);
        assertEquals(10, props.getRegistry().getInstance().getWeight());
    }

    @Test
    void testInstancePropertiesWithZeroWeight() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setWeight(0);
        assertEquals(0, props.getRegistry().getInstance().getWeight());
    }

    @Test
    void testInstancePropertiesWithNegativeWeight() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setWeight(-1);
        assertEquals(-1, props.getRegistry().getInstance().getWeight());
    }

    @Test
    void testInstancePropertiesWithMetadata() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        HashMap<String, String> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("key2", "value2");
        props.getRegistry().getInstance().setMetadata(metadata);
        assertEquals(metadata, props.getRegistry().getInstance().getMetadata());
    }

    @Test
    void testInstancePropertiesWithNullMetadata() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setMetadata(null);
        assertNull(props.getRegistry().getInstance().getMetadata());
    }

    @Test
    void testInstancePropertiesWithEmptyMetadata() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setMetadata(new HashMap<>());
        assertTrue(props.getRegistry().getInstance().getMetadata().isEmpty());
    }
}
