package io.github.cuihairu.redis.streaming.source;

import io.github.cuihairu.redis.streaming.source.impl.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Integration example demonstrating the source connectors functionality
 */
@Slf4j
@Tag("integration")
public class SourceIntegrationExample {

    @Test
    public void testSourceIntegration() throws Exception {
        log.info("=== Source Module Integration Example ===");

        // Test HTTP API source
        testHttpApiSource();

        // Test File system source
        testFileSystemSource();

        // Test IoT device source
        testIoTDeviceSource();

        // Test source factory
        testSourceFactory();
    }

    private static void testHttpApiSource() throws Exception {
        log.info("=== Testing HTTP API Source ===");

        SourceConfiguration config = DefaultSourceConfiguration.builder()
                .name("http-api-test")
                .type("http")
                .property("url", "https://httpbin.org/json")
                .property("method", "GET")
                .pollingIntervalMs(0) // Manual polling for demo
                .build();

        HttpApiSourceConnector connector = new HttpApiSourceConnector(config);

        // Set up event listener
        connector.setEventListener(new LoggingEventListener());

        try {
            connector.start().get(10, TimeUnit.SECONDS);
            log.info("HTTP connector started");

            // Poll for data
            List<SourceRecord> records = connector.poll();
            log.info("Polled {} records from HTTP API", records.size());

            for (SourceRecord record : records) {
                log.info("HTTP Record - Topic: {}, Key: {}, Headers: {}",
                        record.getTopic(), record.getKey(), record.getHeaders());
            }

        } catch (Exception e) {
            log.error("HTTP API test failed (this is expected if no internet connection)", e);
        } finally {
            connector.stop().get(5, TimeUnit.SECONDS);
            log.info("HTTP connector stopped");
        }
    }

    private static void testFileSystemSource() throws Exception {
        log.info("=== Testing File System Source ===");

        // Create a temporary directory for testing
        Path tempDir = Files.createTempDirectory("source-test");
        log.info("Created temp directory: {}", tempDir);

        SourceConfiguration config = DefaultSourceConfiguration.builder()
                .name("file-system-test")
                .type("file")
                .property("directory", tempDir.toString())
                .property("file.pattern", "*.txt")
                .property("watch.mode", "all")
                .property("read.existing", false)
                .pollingIntervalMs(0) // Manual polling for demo
                .build();

        FileSystemSourceConnector connector = new FileSystemSourceConnector(config);
        connector.setEventListener(new LoggingEventListener());

        try {
            connector.start().get(5, TimeUnit.SECONDS);
            log.info("File system connector started");

            // Create some test files
            Path testFile1 = tempDir.resolve("test1.txt");
            Files.writeString(testFile1, "Hello World 1");

            Path testFile2 = tempDir.resolve("test2.txt");
            Files.writeString(testFile2, "Hello World 2");

            // Wait a bit for file events to be processed
            Thread.sleep(1000);

            // Poll for records
            List<SourceRecord> records = connector.poll();
            log.info("Polled {} records from file system", records.size());

            for (SourceRecord record : records) {
                log.info("File Record - Topic: {}, Key: {}, Headers: {}",
                        record.getTopic(), record.getKey(), record.getHeaders());
            }

            // Modify a file
            Files.writeString(testFile1, "Modified content");
            Thread.sleep(500);

            // Poll again
            List<SourceRecord> moreRecords = connector.poll();
            log.info("Polled {} more records after modification", moreRecords.size());

        } finally {
            connector.stop().get(5, TimeUnit.SECONDS);
            log.info("File system connector stopped");

            // Clean up temp directory
            Files.deleteIfExists(tempDir.resolve("test1.txt"));
            Files.deleteIfExists(tempDir.resolve("test2.txt"));
            Files.deleteIfExists(tempDir);
        }
    }

    private static void testIoTDeviceSource() throws Exception {
        log.info("=== Testing IoT Device Source ===");

        SourceConfiguration config = DefaultSourceConfiguration.builder()
                .name("iot-device-test")
                .type("iot")
                .property("device.count", 3)
                .property("device.types", "temperature,humidity,pressure")
                .property("data.interval.ms", 500L)
                .property("simulation.mode", true)
                .pollingIntervalMs(0) // Manual polling for demo
                .build();

        IoTDeviceSourceConnector connector = new IoTDeviceSourceConnector(config);
        connector.setEventListener(new LoggingEventListener());

        try {
            connector.start().get(5, TimeUnit.SECONDS);
            log.info("IoT device connector started");

            // Wait for some data generation
            Thread.sleep(1000);

            // Poll for device data
            List<SourceRecord> records = connector.poll();
            log.info("Polled {} records from IoT devices", records.size());

            for (SourceRecord record : records) {
                log.info("IoT Record - Topic: {}, Key: {}, Device: {}",
                        record.getTopic(), record.getKey(),
                        record.getHeaders().get("iot.device.type"));
            }

            // Get device status
            Map<String, IoTDeviceSourceConnector.IoTDevice> devices = connector.getDevices();
            log.info("Active devices: {}", devices.size());
            devices.forEach((id, device) -> {
                log.info("Device {}: type={}, status={}, lastSeen={}",
                        id, device.getType(), device.getStatus(), device.getLastSeen());
            });

            // Simulate device events
            String deviceId = devices.keySet().iterator().next();
            connector.simulateDeviceEvent(deviceId, "disconnect");
            connector.simulateDeviceEvent(deviceId, "connect");

            Thread.sleep(500);

            // Poll for event records
            List<SourceRecord> eventRecords = connector.poll();
            log.info("Polled {} event records", eventRecords.size());

        } finally {
            connector.stop().get(5, TimeUnit.SECONDS);
            log.info("IoT device connector stopped");
        }
    }

    private static void testSourceFactory() throws Exception {
        log.info("=== Testing Source Factory ===");

        SourceConnectorFactory factory = new SourceConnectorFactory();
        log.info("Registered connector types: {}", factory.getRegisteredTypes());

        // Test HTTP connector factory method
        SourceConnector httpConnector = factory.createHttpConnector(
                "factory-http", "https://httpbin.org/uuid");
        log.info("Created HTTP connector: {}", httpConnector.getName());

        // Test file connector factory method
        Path tempDir = Files.createTempDirectory("factory-test");
        SourceConnector fileConnector = factory.createFileConnector(
                "factory-file", tempDir.toString(), "*.log");
        log.info("Created file connector: {}", fileConnector.getName());

        // Test IoT connector factory method
        SourceConnector iotConnector = factory.createIoTConnector(
                "factory-iot", 5, "motion,light");
        log.info("Created IoT connector: {}", iotConnector.getName());

        // Clean up
        Files.deleteIfExists(tempDir);
        log.info("Source factory test completed");
    }

    /**
     * Simple event listener for logging
     */
    private static class LoggingEventListener implements SourceEventListener {

        @Override
        public void onConnectorStarted(String connectorName) {
            log.info("Event: Connector started - {}", connectorName);
        }

        @Override
        public void onConnectorStopped(String connectorName) {
            log.info("Event: Connector stopped - {}", connectorName);
        }

        @Override
        public void onRecordsPolled(String connectorName, int recordCount) {
            log.debug("Event: Records polled - {} records from {}", recordCount, connectorName);
        }

        @Override
        public void onConnectorError(String connectorName, Throwable error) {
            log.warn("Event: Connector error - {} : {}", connectorName, error.getMessage());
        }

        @Override
        public void onHealthStatusChanged(String connectorName, SourceHealthStatus oldStatus, SourceHealthStatus newStatus) {
            log.info("Event: Health status changed - {} : {} -> {}",
                    connectorName, oldStatus.getStatus(), newStatus.getStatus());
        }
    }
}