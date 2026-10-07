package io.github.cuihairu.redis.streaming.cdc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder for creating CDC configurations
 */
public class CDCConfigurationBuilder {

    private String name;
    private String username;
    private String password;
    private int batchSize = 100;
    private long pollingIntervalMs = 1000;
    private final Map<String, Object> properties = new HashMap<>();

    /**
     * Set connector name
     */
    public CDCConfigurationBuilder name(String name) {
        this.name = name;
        return this;
    }

    /**
     * Set database username
     */
    public CDCConfigurationBuilder username(String username) {
        this.username = username;
        return this;
    }

    /**
     * Set database password
     */
    public CDCConfigurationBuilder password(String password) {
        this.password = password;
        return this;
    }

    /**
     * Set batch size for polling
     */
    public CDCConfigurationBuilder batchSize(int batchSize) {
        this.batchSize = batchSize;
        return this;
    }

    /**
     * Set polling interval in milliseconds
     */
    public CDCConfigurationBuilder pollingIntervalMs(long pollingIntervalMs) {
        this.pollingIntervalMs = pollingIntervalMs;
        return this;
    }

    /**
     * Set a property
     */
    public CDCConfigurationBuilder property(String key, Object value) {
        this.properties.put(key, value);
        return this;
    }

    /**
     * Set multiple properties
     */
    public CDCConfigurationBuilder properties(Map<String, Object> properties) {
        this.properties.putAll(properties);
        return this;
    }

    // MySQL-specific configuration methods

    /**
     * Set MySQL hostname
     */
    public CDCConfigurationBuilder mysqlHostname(String hostname) {
        return property("hostname", hostname);
    }

    /**
     * Set MySQL port
     */
    public CDCConfigurationBuilder mysqlPort(int port) {
        return property("port", port);
    }

    /**
     * Set MySQL server ID
     */
    public CDCConfigurationBuilder mysqlServerId(long serverId) {
        return property("server.id", serverId);
    }

    /**
     * Set MySQL binlog filename
     */
    public CDCConfigurationBuilder mysqlBinlogFilename(String filename) {
        return property("binlog.filename", filename);
    }

    /**
     * Set MySQL binlog position
     */
    public CDCConfigurationBuilder mysqlBinlogPosition(long position) {
        return property("binlog.position", position);
    }

    // PostgreSQL-specific configuration methods

    /**
     * Set PostgreSQL hostname
     */
    public CDCConfigurationBuilder postgresqlHostname(String hostname) {
        return property("hostname", hostname);
    }

    /**
     * Set PostgreSQL port
     */
    public CDCConfigurationBuilder postgresqlPort(int port) {
        return property("port", port);
    }

    /**
     * Set PostgreSQL database name
     */
    public CDCConfigurationBuilder postgresqlDatabase(String database) {
        return property("database", database);
    }

    /**
     * Set PostgreSQL replication slot name
     */
    public CDCConfigurationBuilder postgresqlSlotName(String slotName) {
        return property("slot.name", slotName);
    }

    /**
     * Set PostgreSQL publication name
     */
    public CDCConfigurationBuilder postgresqlPublicationName(String publicationName) {
        return property("publication.name", publicationName);
    }

    /**
     * Set PostgreSQL status interval
     */
    public CDCConfigurationBuilder postgresqlStatusInterval(long intervalMs) {
        return property("status.interval.ms", intervalMs);
    }

    // Database polling-specific configuration methods

    /**
     * Set JDBC URL for database polling
     */
    public CDCConfigurationBuilder jdbcUrl(String jdbcUrl) {
        return property("jdbc.url", jdbcUrl);
    }

    /**
     * Set JDBC driver class
     */
    public CDCConfigurationBuilder driverClass(String driverClass) {
        return property("driver.class", driverClass);
    }

    /**
     * Set tables to poll (comma-separated)
     */
    public CDCConfigurationBuilder tables(String tables) {
        return property("tables", tables);
    }

    /**
     * Set timestamp column for polling
     */
    public CDCConfigurationBuilder timestampColumn(String column) {
        return property("timestamp.column", column);
    }

    /**
     * Set incremental column for polling
     */
    public CDCConfigurationBuilder incrementalColumn(String column) {
        return property("incremental.column", column);
    }

    /**
     * Set query timeout in seconds
     */
    public CDCConfigurationBuilder queryTimeout(int timeoutSeconds) {
        return property("query.timeout.seconds", timeoutSeconds);
    }

    /**
     * Build the configuration
     */
    public CDCConfiguration build() {
        validateCore(name, batchSize, pollingIntervalMs);

        return new DefaultCDCConfiguration(name, username, password, batchSize, pollingIntervalMs, properties);
    }

    /**
     * Default implementation of CDCConfiguration
     */
    private static class DefaultCDCConfiguration implements CDCConfiguration {
        private final String name;
        private final String username;
        private final String password;
        private final int batchSize;
        private final long pollingIntervalMs;
        private final Map<String, Object> properties;

        public DefaultCDCConfiguration(String name, String username, String password,
                                     int batchSize, long pollingIntervalMs,
                                     Map<String, Object> properties) {
            this.name = name;
            this.username = username;
            this.password = password;
            this.batchSize = batchSize;
            this.pollingIntervalMs = pollingIntervalMs;
            this.properties = new HashMap<>(properties);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getType() {
            return (String) properties.getOrDefault("type", "unknown");
        }

        @Override
        public Map<String, Object> getProperties() {
            return new HashMap<>(properties);
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
        public String getDatabaseUrl() {
            return (String) properties.get("database.url");
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public List<String> getTableIncludes() {
            Object value = properties.get("table.includes");
            if (value instanceof List<?>) {
                List<?> list = (List<?>) value;
                return list.stream()
                    .filter(item -> item instanceof String)
                    .map(item -> (String) item)
                    .collect(java.util.stream.Collectors.toList());
            }
            return java.util.Collections.emptyList();
        }

        @Override
        public List<String> getTableExcludes() {
            Object value = properties.get("table.excludes");
            if (value instanceof List<?>) {
                List<?> list = (List<?>) value;
                return list.stream()
                    .filter(item -> item instanceof String)
                    .map(item -> (String) item)
                    .collect(java.util.stream.Collectors.toList());
            }
            return java.util.Collections.emptyList();
        }

        @Override
        public long getPollingIntervalMs() {
            return pollingIntervalMs;
        }

        @Override
        public int getBatchSize() {
            return batchSize;
        }

        @Override
        public boolean isAutoStart() {
            return booleanProperty("auto.start", false);
        }

        @Override
        public boolean isSnapshotEnabled() {
            return booleanProperty("snapshot.enabled", false);
        }

        /**
         * Reads a boolean property tolerating the string form YAML/JSON configs produce —
         * the raw {@code (Boolean)} cast threw ClassCastException for {@code "true"} (CDC-L2).
         */
        private boolean booleanProperty(String key, boolean defaultValue) {
            Object value = properties.get(key);
            if (value == null) {
                return defaultValue;
            }
            if (value instanceof Boolean b) {
                return b;
            }
            if (value instanceof String s) {
                String t = s.trim();
                if (t.equalsIgnoreCase("true")) {
                    return true;
                }
                if (t.equalsIgnoreCase("false")) {
                    return false;
                }
                throw new IllegalArgumentException(
                        "Property \"" + key + "\" must be \"true\" or \"false\", got: " + s);
            }
            throw new IllegalArgumentException(
                    "Property \"" + key + "\" must be a boolean or the string \"true\"/\"false\", got: " + value);
        }

        @Override
        public String getSnapshotMode() {
            return (String) properties.getOrDefault("snapshot.mode", "initial");
        }

        @Override
        public void validate() throws IllegalArgumentException {
            validateCore(name, batchSize, pollingIntervalMs);
        }
    }

    /**
     * Shared contract of {@link CDCConfigurationBuilder#build()} and
     * {@link CDCConfiguration#validate()}: name required, batch size positive (a
     * non-positive one leaves doPoll draining nothing while the event queue grows,
     * CDC-L2), polling interval non-negative (0 is the documented "no scheduled
     * push" sentinel; a negative value is only ever a misconfiguration).
     */
    private static void validateCore(String name, int batchSize, long pollingIntervalMs) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Connector name is required");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0, got: " + batchSize);
        }
        if (pollingIntervalMs < 0) {
            throw new IllegalArgumentException("pollingIntervalMs must be >= 0, got: " + pollingIntervalMs);
        }
    }

    // Static factory methods for common configurations

    /**
     * Create a builder for MySQL binlog CDC
     */
    public static CDCConfigurationBuilder forMySQLBinlog(String name) {
        return new CDCConfigurationBuilder().name(name);
    }

    /**
     * Create a builder for PostgreSQL logical replication CDC
     */
    public static CDCConfigurationBuilder forPostgreSQLLogicalReplication(String name) {
        return new CDCConfigurationBuilder().name(name);
    }

    /**
     * Create a builder for database polling CDC
     */
    public static CDCConfigurationBuilder forDatabasePolling(String name) {
        return new CDCConfigurationBuilder().name(name);
    }
}