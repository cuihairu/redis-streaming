package io.github.cuihairu.redis.streaming.sink;

import io.github.cuihairu.redis.streaming.sink.impl.DefaultSinkConfiguration;
import io.github.cuihairu.redis.streaming.sink.impl.FileSinkConnector;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Integration example demonstrating the sink connectors functionality
 */
@Slf4j
@Tag("integration")
public class SinkIntegrationExample {

    @Test
    public void testSinkIntegration() throws Exception {
        log.info("=== Sink Module Integration Example ===");

        // Test File sink
        testFileSink();

        // Test sink factory
        testSinkFactory();

        // Test metrics and health monitoring
        testMetricsAndHealth();
    }

    private static void testFileSink() throws Exception {
        log.info("=== Testing File Sink ===");

        // Create temporary directory for testing
        Path tempDir = Files.createTempDirectory("sink-test");
        log.info("Created temp directory: {}", tempDir);

        try {
            SinkConfiguration config = DefaultSinkConfiguration.builder()
                    .name("file-sink-test")
                    .type("file")
                    .property("directory", tempDir.toString())
                    .property("format", "JSON")
                    .property("filename.pattern", "test-data-{timestamp}.json")
                    .property("append", true)
                    .batchSize(5)
                    .autoFlush(false) // Manual flush for demo
                    .build();

            FileSinkConnector connector = new FileSinkConnector(config);
            connector.setEventListener(new LoggingEventListener());

            // Start connector
            connector.start().get(10, TimeUnit.SECONDS);
            log.info("File sink connector started");

            // Create test records
            List<SinkRecord> records = List.of(
                    new SinkRecord("events", "event1", Map.of("type", "click", "user", "alice")),
                    new SinkRecord("events", "event2", Map.of("type", "view", "user", "bob")),
                    new SinkRecord("events", "event3", Map.of("type", "purchase", "user", "charlie"))
            );

            // Write records
            SinkResult result = connector.write(records).get(10, TimeUnit.SECONDS);
            log.info("Write result: {} records written, status: {}",
                    result.getRecordsWritten(), result.getStatus());

            // Flush data
            connector.flush().get(10, TimeUnit.SECONDS);
            log.info("Data flushed to file: {}", connector.getCurrentFile());

            // Read and display file contents
            if (Files.exists(connector.getCurrentFile())) {
                List<String> lines = Files.readAllLines(connector.getCurrentFile());
                log.info("File contents ({} lines):", lines.size());
                lines.forEach(line -> log.info("  {}", line));
            }

            // Get metrics
            SinkMetrics metrics = connector.getMetrics();
            log.info("Metrics: {} records written, {} requests, avg latency: {}ms",
                    metrics.getRecordsWritten(), metrics.getWriteRequests(),
                    metrics.getAverageWriteLatencyMs());

            // Stop connector
            connector.stop().get(10, TimeUnit.SECONDS);
            log.info("File sink connector stopped");

        } finally {
            // Clean up temp directory
            try {
                Files.walk(tempDir)
                        .map(Path::toFile)
                        .forEach(file -> file.delete());
                Files.deleteIfExists(tempDir);
            } catch (IOException e) {
                log.warn("Error cleaning up temp directory", e);
            }
        }
    }

    private static void testSinkFactory() throws Exception {
        log.info("=== Testing Sink Factory ===");

        SinkConnectorFactory factory = new SinkConnectorFactory();
        log.info("Registered connector types: {}", factory.getRegisteredTypes());

        // Test file connector factory method
        Path tempDir = Files.createTempDirectory("factory-test");
        try {
            SinkConnector fileConnector = factory.createFileConnector(
                    "factory-file", tempDir.toString(), "TEXT");
            log.info("Created file connector: {}", fileConnector.getName());

            // Test writing with factory-created connector
            fileConnector.start().get(5, TimeUnit.SECONDS);

            List<SinkRecord> records = List.of(
                    new SinkRecord("logs", "log1", "Application started"),
                    new SinkRecord("logs", "log2", "Processing request")
            );

            SinkResult result = fileConnector.write(records).get(5, TimeUnit.SECONDS);
            log.info("Factory connector write result: {} records", result.getRecordsWritten());

            fileConnector.stop().get(5, TimeUnit.SECONDS);

        } finally {
            // Clean up
            try {
                Files.walk(tempDir)
                        .map(Path::toFile)
                        .forEach(file -> file.delete());
                Files.deleteIfExists(tempDir);
            } catch (IOException e) {
                log.warn("Error cleaning up temp directory", e);
            }
        }
    }

    private static void testMetricsAndHealth() throws Exception {
        log.info("=== Testing Metrics and Health Monitoring ===");

        Path tempDir = Files.createTempDirectory("metrics-test");
        try {
            SinkConfiguration config = DefaultSinkConfiguration.builder()
                    .name("metrics-test")
                    .type("file")
                    .property("directory", tempDir.toString())
                    .property("format", "CSV")
                    .property("filename", "metrics-test.csv")
                    .batchSize(2)
                    .build();

            FileSinkConnector connector = new FileSinkConnector(config);

            // Set up health monitoring
            CountDownLatch healthChangeLatch = new CountDownLatch(1);
            connector.setEventListener(new SinkEventListener() {
                @Override
                public void onConnectorStarted(String connectorName) {
                    log.info("Health Event: Connector started - {}", connectorName);
                }

                @Override
                public void onRecordsWritten(String connectorName, SinkResult result) {
                    log.info("Health Event: {} records written - {}",
                            result.getRecordsWritten(), connectorName);
                }

                @Override
                public void onHealthStatusChanged(String connectorName,
                                                SinkHealthStatus oldStatus, SinkHealthStatus newStatus) {
                    log.info("Health Event: Status changed from {} to {} - {}",
                            oldStatus.getStatus(), newStatus.getStatus(), connectorName);
                    healthChangeLatch.countDown();
                }
            });

            // Start and monitor
            connector.start().get(5, TimeUnit.SECONDS);

            // Check initial health
            SinkHealthStatus health = connector.getHealthStatus();
            log.info("Initial health: {} - {}", health.getStatus(), health.getMessage());

            // Write multiple batches to see metrics evolution
            for (int batch = 1; batch <= 3; batch++) {
                List<SinkRecord> records = List.of(
                        new SinkRecord("batch" + batch, "record1", "Data " + batch + ".1"),
                        new SinkRecord("batch" + batch, "record2", "Data " + batch + ".2")
                );

                connector.write(records).get(5, TimeUnit.SECONDS);

                SinkMetrics metrics = connector.getMetrics();
                log.info("Batch {} metrics: {} total records, success rate: {:.2f}",
                        batch, metrics.getTotalRecords(), metrics.getSuccessRate());
            }

            // Final metrics
            SinkMetrics finalMetrics = connector.getMetrics();
            log.info("Final metrics: {} records, {} requests, {} flushes, avg latency: {:.2f}ms",
                    finalMetrics.getRecordsWritten(),
                    finalMetrics.getWriteRequests(),
                    finalMetrics.getFlushCount(),
                    finalMetrics.getAverageWriteLatencyMs());

            connector.stop().get(5, TimeUnit.SECONDS);

        } finally {
            // Clean up
            try {
                Files.walk(tempDir)
                        .map(Path::toFile)
                        .forEach(file -> file.delete());
                Files.deleteIfExists(tempDir);
            } catch (IOException e) {
                log.warn("Error cleaning up temp directory", e);
            }
        }
    }

    /**
     * Simple event listener for logging
     */
    private static class LoggingEventListener implements SinkEventListener {

        @Override
        public void onConnectorStarted(String connectorName) {
            log.info("Event: Connector started - {}", connectorName);
        }

        @Override
        public void onConnectorStopped(String connectorName) {
            log.info("Event: Connector stopped - {}", connectorName);
        }

        @Override
        public void onRecordsWritten(String connectorName, SinkResult result) {
            log.debug("Event: {} records written to {} - status: {}",
                    result.getRecordsWritten(), connectorName, result.getStatus());
        }

        @Override
        public void onConnectorError(String connectorName, Throwable error) {
            log.warn("Event: Connector error - {} : {}", connectorName, error.getMessage());
        }

        @Override
        public void onDataFlushed(String connectorName) {
            log.debug("Event: Data flushed - {}", connectorName);
        }

        @Override
        public void onHealthStatusChanged(String connectorName, SinkHealthStatus oldStatus, SinkHealthStatus newStatus) {
            log.info("Event: Health status changed - {} : {} -> {}",
                    connectorName, oldStatus.getStatus(), newStatus.getStatus());
        }
    }
}