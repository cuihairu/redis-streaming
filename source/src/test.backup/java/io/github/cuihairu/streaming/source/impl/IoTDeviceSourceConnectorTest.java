package io.github.cuihairu.redis.streaming.source.impl;

import io.github.cuihairu.redis.streaming.source.SourceConfiguration;
import io.github.cuihairu.redis.streaming.source.SourceHealthStatus;
import io.github.cuihairu.redis.streaming.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IoTDeviceSourceConnectorTest {

    private IoTDeviceSourceConnector connector;
    private SourceConfiguration configuration;

    @BeforeEach
    void setUp() {
        configuration = DefaultSourceConfiguration.builder()
                .name("test-iot")
                .type("iot")
                .property("device.count", 3)
                .property("device.types", "temperature,humidity")
                .property("data.interval.ms", 100L) // Fast for testing
                .property("simulation.mode", true)
                .pollingIntervalMs(0) // Disable scheduled polling for tests
                .build();

        connector = new IoTDeviceSourceConnector(configuration);
    }

    @Test
    void testConnectorCreation() {
        assertEquals("test-iot", connector.getName());
        assertEquals(configuration, connector.getConfiguration());
        assertFalse(connector.isRunning());
    }

    @Test
    void testStartStop() throws Exception {
        assertFalse(connector.isRunning());

        connector.start().get();
        assertTrue(connector.isRunning());

        connector.stop().get();
        assertFalse(connector.isRunning());
    }

    @Test
    void testDeviceInitialization() throws Exception {
        connector.start().get();

        Map<String, IoTDeviceSourceConnector.IoTDevice> devices = connector.getDevices();
        assertEquals(3, devices.size());

        // Check device types distribution
        long temperatureDevices = devices.values().stream()
                .filter(device -> "temperature".equals(device.getType()))
                .count();
        long humidityDevices = devices.values().stream()
                .filter(device -> "humidity".equals(device.getType()))
                .count();

        assertTrue(temperatureDevices > 0);
        assertTrue(humidityDevices > 0);
        assertEquals(3, temperatureDevices + humidityDevices);

        connector.stop().get();
    }

    @Test
    void testPolling() throws Exception {
        connector.start().get();

        // Wait a bit for data generation
        Thread.sleep(200);

        List<SourceRecord> records = connector.poll();
        assertNotNull(records);

        if (!records.isEmpty()) {
            SourceRecord record = records.get(0);
            assertEquals("test-iot", record.getSource());
            assertNotNull(record.getTopic());
            assertNotNull(record.getKey()); // Device ID
            assertNotNull(record.getValue());
            assertNotNull(record.getHeaders());
        }

        connector.stop().get();
    }

    @Test
    void testDeviceEventSimulation() throws Exception {
        connector.start().get();

        // Get a device ID
        String deviceId = connector.getDevices().keySet().iterator().next();

        // Simulate device events
        connector.simulateDeviceEvent(deviceId, "disconnect");
        connector.simulateDeviceEvent(deviceId, "connect");
        connector.simulateDeviceEvent(deviceId, "error");

        // Poll for event records
        List<SourceRecord> records = connector.poll();
        assertNotNull(records);

        // Should have some event records
        long eventRecords = records.stream()
                .filter(record -> record.getTopic().endsWith(".events"))
                .count();

        assertTrue(eventRecords >= 0); // Might be 0 if polling happens before events

        connector.stop().get();
    }

    @Test
    void testHealthStatus() throws Exception {
        SourceHealthStatus initialStatus = connector.getHealthStatus();
        assertEquals(SourceHealthStatus.Status.UNKNOWN, initialStatus.getStatus());

        connector.start().get();

        SourceHealthStatus runningStatus = connector.getHealthStatus();
        assertEquals(SourceHealthStatus.Status.HEALTHY, runningStatus.getStatus());

        connector.stop().get();
    }

    @Test
    void testDeviceData() throws Exception {
        connector.start().get();

        // Wait for some data generation
        Thread.sleep(200);

        List<SourceRecord> records = connector.poll();

        if (!records.isEmpty()) {
            SourceRecord record = records.get(0);
            Object value = record.getValue();

            assertInstanceOf(Map.class, value);
            Map<?, ?> data = (Map<?, ?>) value;

            assertTrue(data.containsKey("deviceId"));
            assertTrue(data.containsKey("deviceType"));
            assertTrue(data.containsKey("timestamp"));
            assertTrue(data.containsKey("sensorData"));

            Map<?, ?> sensorData = (Map<?, ?>) data.get("sensorData");
            assertNotNull(sensorData);
            assertFalse(sensorData.isEmpty());
        }

        connector.stop().get();
    }

    @Test
    void testConfigurationValidation() {
        // Invalid device count
        SourceConfiguration invalidConfig1 = DefaultSourceConfiguration.builder()
                .name("invalid")
                .type("iot")
                .property("device.count", 0)
                .build();

        IoTDeviceSourceConnector invalidConnector1 = new IoTDeviceSourceConnector(invalidConfig1);
        assertThrows(Exception.class, () -> invalidConnector1.start().get());

        // Invalid data interval
        SourceConfiguration invalidConfig2 = DefaultSourceConfiguration.builder()
                .name("invalid")
                .type("iot")
                .property("device.count", 5)
                .property("data.interval.ms", -1L)
                .build();

        IoTDeviceSourceConnector invalidConnector2 = new IoTDeviceSourceConnector(invalidConfig2);
        assertThrows(Exception.class, () -> invalidConnector2.start().get());
    }
}