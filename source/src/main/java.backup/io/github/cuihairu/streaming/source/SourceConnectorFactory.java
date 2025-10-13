package io.github.cuihairu.redis.streaming.source;

import io.github.cuihairu.redis.streaming.source.impl.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating source connectors
 */
@Slf4j
public class SourceConnectorFactory {

    private final Map<String, ConnectorCreator> connectorCreators = new ConcurrentHashMap<>();

    public SourceConnectorFactory() {
        // Register built-in connectors
        registerConnector("http", HttpApiSourceConnector::new);
        registerConnector("file", FileSystemSourceConnector::new);
        registerConnector("iot", IoTDeviceSourceConnector::new);
    }

    /**
     * Register a custom connector creator
     *
     * @param type the connector type
     * @param creator the connector creator function
     */
    public void registerConnector(String type, ConnectorCreator creator) {
        connectorCreators.put(type.toLowerCase(), creator);
        log.info("Registered source connector type: {}", type);
    }

    /**
     * Create a source connector from configuration
     *
     * @param configuration the source configuration
     * @return the created source connector
     */
    public SourceConnector createConnector(SourceConfiguration configuration) {
        String type = configuration.getType().toLowerCase();
        ConnectorCreator creator = connectorCreators.get(type);

        if (creator == null) {
            throw new IllegalArgumentException("Unknown source connector type: " + type);
        }

        try {
            SourceConnector connector = creator.create(configuration);
            log.info("Created source connector: {} (type: {})", configuration.getName(), type);
            return connector;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create source connector: " + configuration.getName(), e);
        }
    }

    /**
     * Create an HTTP API source connector
     *
     * @param name connector name
     * @param url HTTP URL to poll
     * @return HTTP API source connector
     */
    public SourceConnector createHttpConnector(String name, String url) {
        SourceConfiguration config = new DefaultSourceConfiguration.Builder()
                .name(name)
                .type("http")
                .property("url", url)
                .build();

        return createConnector(config);
    }

    /**
     * Create an HTTP API source connector with custom configuration
     *
     * @param name connector name
     * @param url HTTP URL to poll
     * @param method HTTP method (GET, POST, etc.)
     * @param headers HTTP headers
     * @param pollingIntervalMs polling interval in milliseconds
     * @return HTTP API source connector
     */
    public SourceConnector createHttpConnector(String name, String url, String method,
                                             Map<String, String> headers, long pollingIntervalMs) {
        DefaultSourceConfiguration.Builder builder = new DefaultSourceConfiguration.Builder()
                .name(name)
                .type("http")
                .property("url", url)
                .property("method", method)
                .pollingIntervalMs(pollingIntervalMs);

        if (headers != null && !headers.isEmpty()) {
            builder.property("headers", headers);
        }

        return createConnector(builder.build());
    }

    /**
     * Create a file system source connector
     *
     * @param name connector name
     * @param directory directory to watch
     * @param filePattern file pattern to match
     * @return file system source connector
     */
    public SourceConnector createFileConnector(String name, String directory, String filePattern) {
        SourceConfiguration config = new DefaultSourceConfiguration.Builder()
                .name(name)
                .type("file")
                .property("directory", directory)
                .property("file.pattern", filePattern)
                .build();

        return createConnector(config);
    }

    /**
     * Create a file system source connector with custom configuration
     *
     * @param name connector name
     * @param directory directory to watch
     * @param filePattern file pattern to match
     * @param watchMode watch mode (create, modify, delete, all)
     * @param readExisting whether to read existing files on startup
     * @return file system source connector
     */
    public SourceConnector createFileConnector(String name, String directory, String filePattern,
                                             String watchMode, boolean readExisting) {
        SourceConfiguration config = new DefaultSourceConfiguration.Builder()
                .name(name)
                .type("file")
                .property("directory", directory)
                .property("file.pattern", filePattern)
                .property("watch.mode", watchMode)
                .property("read.existing", readExisting)
                .build();

        return createConnector(config);
    }

    /**
     * Create an IoT device source connector
     *
     * @param name connector name
     * @param deviceCount number of devices to simulate
     * @param deviceTypes comma-separated list of device types
     * @return IoT device source connector
     */
    public SourceConnector createIoTConnector(String name, int deviceCount, String deviceTypes) {
        SourceConfiguration config = new DefaultSourceConfiguration.Builder()
                .name(name)
                .type("iot")
                .property("device.count", deviceCount)
                .property("device.types", deviceTypes)
                .property("simulation.mode", true)
                .build();

        return createConnector(config);
    }

    /**
     * Create an IoT device source connector with custom configuration
     *
     * @param name connector name
     * @param deviceCount number of devices to simulate
     * @param deviceTypes comma-separated list of device types
     * @param dataIntervalMs data generation interval in milliseconds
     * @param simulationMode whether to run in simulation mode
     * @return IoT device source connector
     */
    public SourceConnector createIoTConnector(String name, int deviceCount, String deviceTypes,
                                            long dataIntervalMs, boolean simulationMode) {
        SourceConfiguration config = new DefaultSourceConfiguration.Builder()
                .name(name)
                .type("iot")
                .property("device.count", deviceCount)
                .property("device.types", deviceTypes)
                .property("data.interval.ms", dataIntervalMs)
                .property("simulation.mode", simulationMode)
                .build();

        return createConnector(config);
    }

    /**
     * Get all registered connector types
     *
     * @return set of registered connector types
     */
    public java.util.Set<String> getRegisteredTypes() {
        return connectorCreators.keySet();
    }

    /**
     * Functional interface for creating connectors
     */
    @FunctionalInterface
    public interface ConnectorCreator {
        SourceConnector create(SourceConfiguration configuration) throws Exception;
    }
}