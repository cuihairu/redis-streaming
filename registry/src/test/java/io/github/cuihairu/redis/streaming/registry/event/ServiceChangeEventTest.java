package io.github.cuihairu.redis.streaming.registry.event;

import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ServiceChangeEvent
 */
class ServiceChangeEventTest {

    private ServiceChangeEvent event;

    @BeforeEach
    void setUp() {
        event = new ServiceChangeEvent();
    }

    @Test
    void testDefaultConstructor() {
        ServiceChangeEvent e = new ServiceChangeEvent();

        assertNull(e.getServiceName());
        assertNull(e.getAction());
        assertNull(e.getInstanceId());
        assertEquals(0, e.getTimestamp());
        assertNull(e.getInstance());
    }

    @Test
    void testParameterizedConstructor() {
        ServiceChangeEvent.InstanceSnapshot instance = new ServiceChangeEvent.InstanceSnapshot();
        instance.setServiceName("test-service");
        instance.setInstanceId("instance-1");

        long timestamp = System.currentTimeMillis();

        ServiceChangeEvent e = new ServiceChangeEvent(
            "test-service",
            ServiceChangeAction.ADDED,
            "instance-1",
            timestamp,
            instance
        );

        assertEquals("test-service", e.getServiceName());
        assertEquals(ServiceChangeAction.ADDED, e.getAction());
        assertEquals("instance-1", e.getInstanceId());
        assertEquals(timestamp, e.getTimestamp());
        assertNotNull(e.getInstance());
        assertEquals("test-service", e.getInstance().getServiceName());
    }

    @Test
    void testSetServiceName() {
        event.setServiceName("my-service");

        assertEquals("my-service", event.getServiceName());
    }

    @Test
    void testSetAction() {
        event.setAction(ServiceChangeAction.REMOVED);

        assertEquals(ServiceChangeAction.REMOVED, event.getAction());
    }

    @Test
    void testSetInstanceId() {
        event.setInstanceId("instance-123");

        assertEquals("instance-123", event.getInstanceId());
    }

    @Test
    void testSetTimestamp() {
        long time = System.currentTimeMillis();
        event.setTimestamp(time);

        assertEquals(time, event.getTimestamp());
    }

    @Test
    void testSetInstance() {
        ServiceChangeEvent.InstanceSnapshot instance = new ServiceChangeEvent.InstanceSnapshot();
        instance.setServiceName("test-service");

        event.setInstance(instance);

        assertNotNull(event.getInstance());
        assertEquals("test-service", event.getInstance().getServiceName());
    }

    @Test
    void testInstanceSnapshotDefaultConstructor() {
        ServiceChangeEvent.InstanceSnapshot snapshot = new ServiceChangeEvent.InstanceSnapshot();

        assertNull(snapshot.getServiceName());
        assertNull(snapshot.getInstanceId());
        assertNull(snapshot.getHost());
        assertEquals(0, snapshot.getPort());
        assertNull(snapshot.getProtocol());
        assertFalse(snapshot.isEnabled());
        assertFalse(snapshot.isHealthy());
        assertEquals(0, snapshot.getWeight());
        assertNull(snapshot.getMetadata());
    }

    @Test
    void testInstanceSnapshotSetServiceName() {
        ServiceChangeEvent.InstanceSnapshot snapshot = new ServiceChangeEvent.InstanceSnapshot();
        snapshot.setServiceName("test-service");

        assertEquals("test-service", snapshot.getServiceName());
    }

    @Test
    void testInstanceSnapshotSetInstanceId() {
        ServiceChangeEvent.InstanceSnapshot snapshot = new ServiceChangeEvent.InstanceSnapshot();
        snapshot.setInstanceId("instance-1");

        assertEquals("instance-1", snapshot.getInstanceId());
    }

    @Test
    void testInstanceSnapshotSetHost() {
        ServiceChangeEvent.InstanceSnapshot snapshot = new ServiceChangeEvent.InstanceSnapshot();
        snapshot.setHost("localhost");

        assertEquals("localhost", snapshot.getHost());
    }

    @Test
    void testInstanceSnapshotSetPort() {
        ServiceChangeEvent.InstanceSnapshot snapshot = new ServiceChangeEvent.InstanceSnapshot();
        snapshot.setPort(8080);

        assertEquals(8080, snapshot.getPort());
    }

    @Test
    void testInstanceSnapshotSetProtocol() {
        ServiceChangeEvent.InstanceSnapshot snapshot = new ServiceChangeEvent.InstanceSnapshot();
        snapshot.setProtocol("HTTP");

        assertEquals("HTTP", snapshot.getProtocol());
    }

    @Test
    void testInstanceSnapshotSetEnabled() {
        ServiceChangeEvent.InstanceSnapshot snapshot = new ServiceChangeEvent.InstanceSnapshot();
        snapshot.setEnabled(true);

        assertTrue(snapshot.isEnabled());
    }

    @Test
    void testInstanceSnapshotSetHealthy() {
        ServiceChangeEvent.InstanceSnapshot snapshot = new ServiceChangeEvent.InstanceSnapshot();
        snapshot.setHealthy(true);

        assertTrue(snapshot.isHealthy());
    }

    @Test
    void testInstanceSnapshotSetWeight() {
        ServiceChangeEvent.InstanceSnapshot snapshot = new ServiceChangeEvent.InstanceSnapshot();
        snapshot.setWeight(5);

        assertEquals(5, snapshot.getWeight());
    }

    @Test
    void testInstanceSnapshotSetMetadata() {
        ServiceChangeEvent.InstanceSnapshot snapshot = new ServiceChangeEvent.InstanceSnapshot();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");
        metadata.put("region", "us-east-1");

        snapshot.setMetadata(metadata);

        assertNotNull(snapshot.getMetadata());
        assertEquals(2, snapshot.getMetadata().size());
        assertEquals("1.0.0", snapshot.getMetadata().get("version"));
        assertEquals("us-east-1", snapshot.getMetadata().get("region"));
    }

    @Test
    void testFullEventCreation() {
        ServiceChangeEvent.InstanceSnapshot instance = new ServiceChangeEvent.InstanceSnapshot();
        instance.setServiceName("user-service");
        instance.setInstanceId("instance-001");
        instance.setHost("192.168.1.100");
        instance.setPort(9090);
        instance.setProtocol("HTTPS");
        instance.setEnabled(true);
        instance.setHealthy(true);
        instance.setWeight(10);

        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "2.0.0");
        metadata.put("env", "production");
        instance.setMetadata(metadata);

        event.setServiceName("user-service");
        event.setAction(ServiceChangeAction.UPDATED);
        event.setInstanceId("instance-001");
        event.setTimestamp(System.currentTimeMillis());
        event.setInstance(instance);

        assertEquals("user-service", event.getServiceName());
        assertEquals(ServiceChangeAction.UPDATED, event.getAction());
        assertEquals("instance-001", event.getInstanceId());
        assertNotNull(event.getInstance());
        assertEquals("user-service", event.getInstance().getServiceName());
        assertEquals("instance-001", event.getInstance().getInstanceId());
        assertEquals("192.168.1.100", event.getInstance().getHost());
        assertEquals(9090, event.getInstance().getPort());
        assertEquals("HTTPS", event.getInstance().getProtocol());
        assertTrue(event.getInstance().isEnabled());
        assertTrue(event.getInstance().isHealthy());
        assertEquals(10, event.getInstance().getWeight());
        assertEquals(2, event.getInstance().getMetadata().size());
    }

    @Test
    void testEventWithAllActionTypes() {
        ServiceChangeEvent.InstanceSnapshot instance = new ServiceChangeEvent.InstanceSnapshot();
        instance.setServiceName("test-service");
        instance.setInstanceId("instance-1");

        ServiceChangeAction[] actions = {
            ServiceChangeAction.ADDED,
            ServiceChangeAction.REMOVED,
            ServiceChangeAction.UPDATED,
            ServiceChangeAction.CURRENT,
            ServiceChangeAction.HEALTH_RECOVERY,
            ServiceChangeAction.HEALTH_FAILURE
        };

        for (ServiceChangeAction action : actions) {
            ServiceChangeEvent e = new ServiceChangeEvent();
            e.setServiceName("test-service");
            e.setAction(action);
            e.setInstanceId("instance-1");
            e.setTimestamp(System.currentTimeMillis());
            e.setInstance(instance);

            assertEquals(action, e.getAction());
        }
    }

    @Test
    void testInstanceSnapshotWithNullMetadata() {
        ServiceChangeEvent.InstanceSnapshot snapshot = new ServiceChangeEvent.InstanceSnapshot();
        snapshot.setMetadata(null);

        assertNull(snapshot.getMetadata());
    }

    @Test
    void testInstanceSnapshotWithEmptyMetadata() {
        ServiceChangeEvent.InstanceSnapshot snapshot = new ServiceChangeEvent.InstanceSnapshot();
        snapshot.setMetadata(new HashMap<>());

        assertNotNull(snapshot.getMetadata());
        assertTrue(snapshot.getMetadata().isEmpty());
    }
}
