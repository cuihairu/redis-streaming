package io.github.cuihairu.redis.streaming.sink.impl;

import io.github.cuihairu.redis.streaming.sink.SinkConfiguration;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Default implementation of SinkConfiguration
 */
@Data
@AllArgsConstructor
public class DefaultSinkConfiguration implements SinkConfiguration {

    private final String name;
    private final String type;
    private final Map<String, Object> properties;
    private final int batchSize;
    private final long flushIntervalMs;
    private final int maxRetries;
    private final long retryBackoffMs;
    private final boolean autoStart;
    private final boolean autoFlush;

    public DefaultSinkConfiguration(String name, String type) {
        this(name, type, new HashMap<>(), 100, 5000, 3, 1000, true, true);
    }

    @Override
    public Object getProperty(String key) {
        return properties.get(key);
    }

    @Override
    public Object getProperty(String key, Object defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    @Override
    public void validate() throws IllegalArgumentException {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Connector name cannot be null or empty");
        }

        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Connector type cannot be null or empty");
        }

        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be positive");
        }

        if (flushIntervalMs < 0) {
            throw new IllegalArgumentException("Flush interval cannot be negative");
        }

        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries cannot be negative");
        }

        if (retryBackoffMs < 0) {
            throw new IllegalArgumentException("Retry backoff cannot be negative");
        }

        // Type-specific validation
        validateByType();
    }

    private void validateByType() {
        switch (type.toLowerCase()) {
            case "elasticsearch":
                validateElasticsearchConfiguration();
                break;
            case "database":
                validateDatabaseConfiguration();
                break;
            case "file":
                validateFileConfiguration();
                break;
            default:
                // No specific validation for unknown types
                break;
        }
    }

    private void validateElasticsearchConfiguration() {
        String hosts = (String) getProperty("hosts");
        if (hosts == null || hosts.trim().isEmpty()) {
            throw new IllegalArgumentException("Elasticsearch sink requires 'hosts' property");
        }

        String index = (String) getProperty("index");
        String indexPattern = (String) getProperty("index.pattern");
        if (index == null && indexPattern == null) {
            throw new IllegalArgumentException("Elasticsearch sink requires either 'index' or 'index.pattern' property");
        }
    }

    private void validateDatabaseConfiguration() {
        String jdbcUrl = (String) getProperty("jdbc.url");
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Database sink requires 'jdbc.url' property");
        }

        String username = (String) getProperty("username");
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Database sink requires 'username' property");
        }

        String password = (String) getProperty("password");
        if (password == null) {
            throw new IllegalArgumentException("Database sink requires 'password' property");
        }
    }

    private void validateFileConfiguration() {
        String directory = (String) getProperty("directory");
        if (directory == null || directory.trim().isEmpty()) {
            throw new IllegalArgumentException("File sink requires 'directory' property");
        }

        String format = (String) getProperty("format", "JSON");
        if (!format.matches("(?i)(JSON|TEXT|CSV)")) {
            throw new IllegalArgumentException("Invalid file format: " + format + ". Must be JSON, TEXT, or CSV");
        }

        String rotationSizeStr = String.valueOf(getProperty("rotation.size.bytes", "0"));
        try {
            long rotationSize = Long.parseLong(rotationSizeStr);
            if (rotationSize < 0) {
                throw new IllegalArgumentException("Rotation size cannot be negative");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid rotation size: " + rotationSizeStr);
        }

        String rotationTimeStr = String.valueOf(getProperty("rotation.time.ms", "0"));
        try {
            long rotationTime = Long.parseLong(rotationTimeStr);
            if (rotationTime < 0) {
                throw new IllegalArgumentException("Rotation time cannot be negative");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid rotation time: " + rotationTimeStr);
        }
    }

    /**
     * Builder for creating sink configurations
     */
    public static class Builder {
        private String name;
        private String type;
        private Map<String, Object> properties = new HashMap<>();
        private int batchSize = 100;
        private long flushIntervalMs = 5000;
        private int maxRetries = 3;
        private long retryBackoffMs = 1000;
        private boolean autoStart = true;
        private boolean autoFlush = true;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder property(String key, Object value) {
            this.properties.put(key, value);
            return this;
        }

        public Builder properties(Map<String, Object> properties) {
            this.properties.putAll(properties);
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder flushIntervalMs(long flushIntervalMs) {
            this.flushIntervalMs = flushIntervalMs;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder retryBackoffMs(long retryBackoffMs) {
            this.retryBackoffMs = retryBackoffMs;
            return this;
        }

        public Builder autoStart(boolean autoStart) {
            this.autoStart = autoStart;
            return this;
        }

        public Builder autoFlush(boolean autoFlush) {
            this.autoFlush = autoFlush;
            return this;
        }

        public DefaultSinkConfiguration build() {
            return new DefaultSinkConfiguration(name, type, properties, batchSize, flushIntervalMs,
                    maxRetries, retryBackoffMs, autoStart, autoFlush);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}