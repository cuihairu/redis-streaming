package io.github.cuihairu.redis.streaming.source.impl;

import io.github.cuihairu.redis.streaming.source.SourceConfiguration;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Default implementation of SourceConfiguration
 */
@Data
@AllArgsConstructor
public class DefaultSourceConfiguration implements SourceConfiguration {

    private final String name;
    private final String type;
    private final Map<String, Object> properties;
    private final long pollingIntervalMs;
    private final int batchSize;
    private final boolean autoStart;

    public DefaultSourceConfiguration(String name, String type) {
        this(name, type, new HashMap<>(), 1000, 100, true);
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

        if (pollingIntervalMs < 0) {
            throw new IllegalArgumentException("Polling interval cannot be negative");
        }

        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be positive");
        }

        // Type-specific validation
        validateByType();
    }

    private void validateByType() {
        switch (type.toLowerCase()) {
            case "http":
                validateHttpConfiguration();
                break;
            case "file":
                validateFileConfiguration();
                break;
            case "iot":
                validateIoTConfiguration();
                break;
            default:
                // No specific validation for unknown types
                break;
        }
    }

    private void validateHttpConfiguration() {
        String url = (String) getProperty("url");
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("HTTP source requires 'url' property");
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("Invalid HTTP URL: " + url);
        }

        String method = (String) getProperty("method", "GET");
        if (!method.matches("(?i)(GET|POST|PUT|DELETE)")) {
            throw new IllegalArgumentException("Invalid HTTP method: " + method);
        }
    }

    private void validateFileConfiguration() {
        String directory = (String) getProperty("directory");
        if (directory == null || directory.trim().isEmpty()) {
            throw new IllegalArgumentException("File source requires 'directory' property");
        }

        String watchMode = (String) getProperty("watch.mode", "all");
        if (!watchMode.matches("(?i)(create|modify|delete|all)")) {
            throw new IllegalArgumentException("Invalid watch mode: " + watchMode);
        }
    }

    private void validateIoTConfiguration() {
        String deviceCountStr = String.valueOf(getProperty("device.count", "10"));
        try {
            int deviceCount = Integer.parseInt(deviceCountStr);
            if (deviceCount <= 0) {
                throw new IllegalArgumentException("IoT device count must be positive");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid device count: " + deviceCountStr);
        }

        String dataIntervalStr = String.valueOf(getProperty("data.interval.ms", "5000"));
        try {
            long dataInterval = Long.parseLong(dataIntervalStr);
            if (dataInterval <= 0) {
                throw new IllegalArgumentException("IoT data interval must be positive");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid data interval: " + dataIntervalStr);
        }
    }

    /**
     * Builder for creating source configurations
     */
    public static class Builder {
        private String name;
        private String type;
        private Map<String, Object> properties = new HashMap<>();
        private long pollingIntervalMs = 1000;
        private int batchSize = 100;
        private boolean autoStart = true;

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

        public Builder pollingIntervalMs(long pollingIntervalMs) {
            this.pollingIntervalMs = pollingIntervalMs;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder autoStart(boolean autoStart) {
            this.autoStart = autoStart;
            return this;
        }

        public DefaultSourceConfiguration build() {
            return new DefaultSourceConfiguration(name, type, properties, pollingIntervalMs, batchSize, autoStart);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}