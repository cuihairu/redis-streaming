package io.github.cuihairu.redis.streaming.sink;

import io.github.cuihairu.redis.streaming.sink.impl.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating sink connectors
 */
@Slf4j
public class SinkConnectorFactory {

    private final Map<String, ConnectorCreator> connectorCreators = new ConcurrentHashMap<>();

    public SinkConnectorFactory() {
        // Register built-in connectors
        registerConnector("elasticsearch", ElasticsearchSinkConnector::new);
        registerConnector("database", DatabaseSinkConnector::new);
        registerConnector("file", FileSinkConnector::new);
    }

    /**
     * Register a custom connector creator
     *
     * @param type the connector type
     * @param creator the connector creator function
     */
    public void registerConnector(String type, ConnectorCreator creator) {
        connectorCreators.put(type.toLowerCase(), creator);
        log.info("Registered sink connector type: {}", type);
    }

    /**
     * Create a sink connector from configuration
     *
     * @param configuration the sink configuration
     * @return the created sink connector
     */
    public SinkConnector createConnector(SinkConfiguration configuration) {
        String type = configuration.getType().toLowerCase();
        ConnectorCreator creator = connectorCreators.get(type);

        if (creator == null) {
            throw new IllegalArgumentException("Unknown sink connector type: " + type);
        }

        try {
            SinkConnector connector = creator.create(configuration);
            log.info("Created sink connector: {} (type: {})", configuration.getName(), type);
            return connector;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create sink connector: " + configuration.getName(), e);
        }
    }

    /**
     * Create an Elasticsearch sink connector
     *
     * @param name connector name
     * @param hosts Elasticsearch hosts (comma-separated)
     * @param index default index name
     * @return Elasticsearch sink connector
     */
    public SinkConnector createElasticsearchConnector(String name, String hosts, String index) {
        SinkConfiguration config = DefaultSinkConfiguration.builder()
                .name(name)
                .type("elasticsearch")
                .property("hosts", hosts)
                .property("index", index)
                .build();

        return createConnector(config);
    }

    /**
     * Create an Elasticsearch sink connector with custom configuration
     *
     * @param name connector name
     * @param hosts Elasticsearch hosts
     * @param indexPattern index pattern with date substitution
     * @param dateFormat date format for index pattern
     * @param batchSize batch size for bulk operations
     * @return Elasticsearch sink connector
     */
    public SinkConnector createElasticsearchConnector(String name, String hosts, String indexPattern,
                                                    String dateFormat, int batchSize) {
        SinkConfiguration config = DefaultSinkConfiguration.builder()
                .name(name)
                .type("elasticsearch")
                .property("hosts", hosts)
                .property("index.pattern", indexPattern)
                .property("date.format", dateFormat)
                .batchSize(batchSize)
                .build();

        return createConnector(config);
    }

    /**
     * Create a database sink connector
     *
     * @param name connector name
     * @param jdbcUrl JDBC URL
     * @param username database username
     * @param password database password
     * @param tableName table name
     * @return database sink connector
     */
    public SinkConnector createDatabaseConnector(String name, String jdbcUrl, String username,
                                               String password, String tableName) {
        SinkConfiguration config = DefaultSinkConfiguration.builder()
                .name(name)
                .type("database")
                .property("jdbc.url", jdbcUrl)
                .property("username", username)
                .property("password", password)
                .property("table", tableName)
                .build();

        return createConnector(config);
    }

    /**
     * Create a database sink connector with custom configuration
     *
     * @param name connector name
     * @param jdbcUrl JDBC URL
     * @param username database username
     * @param password database password
     * @param tableName table name
     * @param driverClass JDBC driver class
     * @param autoCreateTable whether to auto-create table
     * @return database sink connector
     */
    public SinkConnector createDatabaseConnector(String name, String jdbcUrl, String username,
                                               String password, String tableName, String driverClass,
                                               boolean autoCreateTable) {
        SinkConfiguration config = DefaultSinkConfiguration.builder()
                .name(name)
                .type("database")
                .property("jdbc.url", jdbcUrl)
                .property("username", username)
                .property("password", password)
                .property("table", tableName)
                .property("driver.class", driverClass)
                .property("auto.create.table", autoCreateTable)
                .build();

        return createConnector(config);
    }

    /**
     * Create a file sink connector
     *
     * @param name connector name
     * @param directory output directory
     * @param format file format (JSON, TEXT, CSV)
     * @return file sink connector
     */
    public SinkConnector createFileConnector(String name, String directory, String format) {
        SinkConfiguration config = DefaultSinkConfiguration.builder()
                .name(name)
                .type("file")
                .property("directory", directory)
                .property("format", format)
                .build();

        return createConnector(config);
    }

    /**
     * Create a file sink connector with custom configuration
     *
     * @param name connector name
     * @param directory output directory
     * @param format file format
     * @param filenamePattern filename pattern
     * @param rotationSizeBytes file rotation size in bytes
     * @param append whether to append to existing files
     * @return file sink connector
     */
    public SinkConnector createFileConnector(String name, String directory, String format,
                                           String filenamePattern, long rotationSizeBytes, boolean append) {
        SinkConfiguration config = DefaultSinkConfiguration.builder()
                .name(name)
                .type("file")
                .property("directory", directory)
                .property("format", format)
                .property("filename.pattern", filenamePattern)
                .property("rotation.size.bytes", rotationSizeBytes)
                .property("append", append)
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
        SinkConnector create(SinkConfiguration configuration) throws Exception;
    }
}