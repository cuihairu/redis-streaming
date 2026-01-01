package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CDCConfiguration interface
 */
class CDCConfigurationTest {

    @Test
    void testInterfaceIsDefined() {
        // Given & When & Then
        assertTrue(CDCConfiguration.class.isInterface());
    }

    @Test
    void testGetNameMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("getName"));
    }

    @Test
    void testGetTypeMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("getType"));
    }

    @Test
    void testGetPropertiesMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("getProperties"));
    }

    @Test
    void testGetPropertyMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("getProperty", String.class));
    }

    @Test
    void testGetPropertyWithDefaultMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("getProperty", String.class, Object.class));
    }

    @Test
    void testGetDatabaseUrlMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("getDatabaseUrl"));
    }

    @Test
    void testGetUsernameMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("getUsername"));
    }

    @Test
    void testGetPasswordMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("getPassword"));
    }

    @Test
    void testGetTableIncludesMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("getTableIncludes"));
    }

    @Test
    void testGetTableExcludesMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("getTableExcludes"));
    }

    @Test
    void testGetPollingIntervalMsMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("getPollingIntervalMs"));
    }

    @Test
    void testGetBatchSizeMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("getBatchSize"));
    }

    @Test
    void testIsAutoStartMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("isAutoStart"));
    }

    @Test
    void testIsSnapshotEnabledMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("isSnapshotEnabled"));
    }

    @Test
    void testGetSnapshotModeMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("getSnapshotMode"));
    }

    @Test
    void testValidateMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConfiguration.class.getMethod("validate"));
    }

    @Test
    void testSimpleImplementation() {
        // Given - create a simple in-memory implementation
        CDCConfiguration config = new CDCConfiguration() {
            @Override
            public String getName() {
                return "test-connector";
            }

            @Override
            public String getType() {
                return "mysql-binlog";
            }

            @Override
            public Map<String, Object> getProperties() {
                Map<String, Object> props = new HashMap<>();
                props.put("host", "localhost");
                props.put("port", 3306);
                return props;
            }

            @Override
            public Object getProperty(String key) {
                return getProperties().get(key);
            }

            @Override
            public Object getProperty(String key, Object defaultValue) {
                Object value = getProperty(key);
                return value != null ? value : defaultValue;
            }

            @Override
            public String getDatabaseUrl() {
                return "jdbc:mysql://localhost:3306/testdb";
            }

            @Override
            public String getUsername() {
                return "testuser";
            }

            @Override
            public String getPassword() {
                return "testpass";
            }

            @Override
            public List<String> getTableIncludes() {
                return Arrays.asList("users", "orders");
            }

            @Override
            public List<String> getTableExcludes() {
                return Arrays.asList("temp_*");
            }

            @Override
            public long getPollingIntervalMs() {
                return 1000;
            }

            @Override
            public int getBatchSize() {
                return 100;
            }

            @Override
            public boolean isAutoStart() {
                return true;
            }

            @Override
            public boolean isSnapshotEnabled() {
                return true;
            }

            @Override
            public String getSnapshotMode() {
                return "initial";
            }

            @Override
            public void validate() throws IllegalArgumentException {
                if (getName() == null || getName().isEmpty()) {
                    throw new IllegalArgumentException("Name cannot be null or empty");
                }
            }
        };

        // When & Then - verify all methods work
        assertEquals("test-connector", config.getName());
        assertEquals("mysql-binlog", config.getType());
        assertNotNull(config.getProperties());
        assertEquals("localhost", config.getProperty("host"));
        assertEquals(3306, config.getProperty("port"));
        assertEquals("default", config.getProperty("nonexistent", "default"));
        assertEquals("jdbc:mysql://localhost:3306/testdb", config.getDatabaseUrl());
        assertEquals("testuser", config.getUsername());
        assertEquals("testpass", config.getPassword());
        assertEquals(2, config.getTableIncludes().size());
        assertEquals(1, config.getTableExcludes().size());
        assertEquals(1000, config.getPollingIntervalMs());
        assertEquals(100, config.getBatchSize());
        assertTrue(config.isAutoStart());
        assertTrue(config.isSnapshotEnabled());
        assertEquals("initial", config.getSnapshotMode());
        assertDoesNotThrow(() -> config.validate());
    }

    @Test
    void testGetProperties() {
        // Given
        CDCConfiguration config = new CDCConfiguration() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public String getType() {
                return "test";
            }

            @Override
            public Map<String, Object> getProperties() {
                Map<String, Object> props = new HashMap<>();
                props.put("key1", "value1");
                props.put("key2", 123);
                return props;
            }

            @Override
            public Object getProperty(String key) {
                return null;
            }

            @Override
            public Object getProperty(String key, Object defaultValue) {
                return null;
            }

            @Override
            public String getDatabaseUrl() {
                return null;
            }

            @Override
            public String getUsername() {
                return null;
            }

            @Override
            public String getPassword() {
                return null;
            }

            @Override
            public List<String> getTableIncludes() {
                return null;
            }

            @Override
            public List<String> getTableExcludes() {
                return null;
            }

            @Override
            public long getPollingIntervalMs() {
                return 0;
            }

            @Override
            public int getBatchSize() {
                return 0;
            }

            @Override
            public boolean isAutoStart() {
                return false;
            }

            @Override
            public boolean isSnapshotEnabled() {
                return false;
            }

            @Override
            public String getSnapshotMode() {
                return null;
            }

            @Override
            public void validate() throws IllegalArgumentException {
            }
        };

        // When
        Map<String, Object> props = config.getProperties();

        // Then
        assertEquals(2, props.size());
        assertEquals("value1", props.get("key1"));
        assertEquals(123, props.get("key2"));
    }

    @Test
    void testGetPropertyWithDefaultValue() {
        // Given
        CDCConfiguration config = new CDCConfiguration() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public String getType() {
                return "test";
            }

            @Override
            public Map<String, Object> getProperties() {
                return new HashMap<>();
            }

            @Override
            public Object getProperty(String key) {
                return "exists".equals(key) ? "value" : null;
            }

            @Override
            public Object getProperty(String key, Object defaultValue) {
                Object value = getProperty(key);
                return value != null ? value : defaultValue;
            }

            @Override
            public String getDatabaseUrl() {
                return null;
            }

            @Override
            public String getUsername() {
                return null;
            }

            @Override
            public String getPassword() {
                return null;
            }

            @Override
            public List<String> getTableIncludes() {
                return null;
            }

            @Override
            public List<String> getTableExcludes() {
                return null;
            }

            @Override
            public long getPollingIntervalMs() {
                return 0;
            }

            @Override
            public int getBatchSize() {
                return 0;
            }

            @Override
            public boolean isAutoStart() {
                return false;
            }

            @Override
            public boolean isSnapshotEnabled() {
                return false;
            }

            @Override
            public String getSnapshotMode() {
                return null;
            }

            @Override
            public void validate() throws IllegalArgumentException {
            }
        };

        // When & Then
        assertEquals("value", config.getProperty("exists"));
        assertNull(config.getProperty("nonexistent"));
        assertEquals("default", config.getProperty("nonexistent", "default"));
    }

    @Test
    void testValidateThrowsException() {
        // Given
        CDCConfiguration config = new CDCConfiguration() {
            @Override
            public String getName() {
                return "";
            }

            @Override
            public String getType() {
                return "test";
            }

            @Override
            public Map<String, Object> getProperties() {
                return new HashMap<>();
            }

            @Override
            public Object getProperty(String key) {
                return null;
            }

            @Override
            public Object getProperty(String key, Object defaultValue) {
                return null;
            }

            @Override
            public String getDatabaseUrl() {
                return null;
            }

            @Override
            public String getUsername() {
                return null;
            }

            @Override
            public String getPassword() {
                return null;
            }

            @Override
            public List<String> getTableIncludes() {
                return null;
            }

            @Override
            public List<String> getTableExcludes() {
                return null;
            }

            @Override
            public long getPollingIntervalMs() {
                return 0;
            }

            @Override
            public int getBatchSize() {
                return 0;
            }

            @Override
            public boolean isAutoStart() {
                return false;
            }

            @Override
            public boolean isSnapshotEnabled() {
                return false;
            }

            @Override
            public String getSnapshotMode() {
                return null;
            }

            @Override
            public void validate() throws IllegalArgumentException {
                if (getName() == null || getName().isEmpty()) {
                    throw new IllegalArgumentException("Name cannot be null or empty");
                }
            }
        };

        // When & Then
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void testTableIncludesAndExcludes() {
        // Given
        CDCConfiguration config = new CDCConfiguration() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public String getType() {
                return "test";
            }

            @Override
            public Map<String, Object> getProperties() {
                return new HashMap<>();
            }

            @Override
            public Object getProperty(String key) {
                return null;
            }

            @Override
            public Object getProperty(String key, Object defaultValue) {
                return null;
            }

            @Override
            public String getDatabaseUrl() {
                return null;
            }

            @Override
            public String getUsername() {
                return null;
            }

            @Override
            public String getPassword() {
                return null;
            }

            @Override
            public List<String> getTableIncludes() {
                return Arrays.asList("users", "orders", "products");
            }

            @Override
            public List<String> getTableExcludes() {
                return Arrays.asList("temp_*", "test_*");
            }

            @Override
            public long getPollingIntervalMs() {
                return 0;
            }

            @Override
            public int getBatchSize() {
                return 0;
            }

            @Override
            public boolean isAutoStart() {
                return false;
            }

            @Override
            public boolean isSnapshotEnabled() {
                return false;
            }

            @Override
            public String getSnapshotMode() {
                return null;
            }

            @Override
            public void validate() throws IllegalArgumentException {
            }
        };

        // When & Then
        assertEquals(3, config.getTableIncludes().size());
        assertTrue(config.getTableIncludes().contains("users"));
        assertTrue(config.getTableIncludes().contains("orders"));
        assertTrue(config.getTableIncludes().contains("products"));

        assertEquals(2, config.getTableExcludes().size());
        assertTrue(config.getTableExcludes().contains("temp_*"));
        assertTrue(config.getTableExcludes().contains("test_*"));
    }

    @Test
    void testPollingConfiguration() {
        // Given
        CDCConfiguration config = new CDCConfiguration() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public String getType() {
                return "polling";
            }

            @Override
            public Map<String, Object> getProperties() {
                return new HashMap<>();
            }

            @Override
            public Object getProperty(String key) {
                return null;
            }

            @Override
            public Object getProperty(String key, Object defaultValue) {
                return null;
            }

            @Override
            public String getDatabaseUrl() {
                return null;
            }

            @Override
            public String getUsername() {
                return null;
            }

            @Override
            public String getPassword() {
                return null;
            }

            @Override
            public List<String> getTableIncludes() {
                return null;
            }

            @Override
            public List<String> getTableExcludes() {
                return null;
            }

            @Override
            public long getPollingIntervalMs() {
                return 5000;
            }

            @Override
            public int getBatchSize() {
                return 1000;
            }

            @Override
            public boolean isAutoStart() {
                return false;
            }

            @Override
            public boolean isSnapshotEnabled() {
                return false;
            }

            @Override
            public String getSnapshotMode() {
                return null;
            }

            @Override
            public void validate() throws IllegalArgumentException {
            }
        };

        // When & Then
        assertEquals("polling", config.getType());
        assertEquals(5000, config.getPollingIntervalMs());
        assertEquals(1000, config.getBatchSize());
    }

    @Test
    void testSnapshotConfiguration() {
        // Given
        CDCConfiguration config = new CDCConfiguration() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public String getType() {
                return "test";
            }

            @Override
            public Map<String, Object> getProperties() {
                return new HashMap<>();
            }

            @Override
            public Object getProperty(String key) {
                return null;
            }

            @Override
            public Object getProperty(String key, Object defaultValue) {
                return null;
            }

            @Override
            public String getDatabaseUrl() {
                return null;
            }

            @Override
            public String getUsername() {
                return null;
            }

            @Override
            public String getPassword() {
                return null;
            }

            @Override
            public List<String> getTableIncludes() {
                return null;
            }

            @Override
            public List<String> getTableExcludes() {
                return null;
            }

            @Override
            public long getPollingIntervalMs() {
                return 0;
            }

            @Override
            public int getBatchSize() {
                return 0;
            }

            @Override
            public boolean isAutoStart() {
                return true;
            }

            @Override
            public boolean isSnapshotEnabled() {
                return true;
            }

            @Override
            public String getSnapshotMode() {
                return "when_needed";
            }

            @Override
            public void validate() throws IllegalArgumentException {
            }
        };

        // When & Then
        assertTrue(config.isAutoStart());
        assertTrue(config.isSnapshotEnabled());
        assertEquals("when_needed", config.getSnapshotMode());
    }

    @Test
    void testConnectorTypes() {
        // Given - test different connector types
        CDCConfiguration mysqlConfig = createConfig("mysql-binlog");
        CDCConfiguration postgresConfig = createConfig("postgres-logical");
        CDCConfiguration pollingConfig = createConfig("polling");

        // When & Then
        assertEquals("mysql-binlog", mysqlConfig.getType());
        assertEquals("postgres-logical", postgresConfig.getType());
        assertEquals("polling", pollingConfig.getType());
    }

    private CDCConfiguration createConfig(String type) {
        return new CDCConfiguration() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public String getType() {
                return type;
            }

            @Override
            public Map<String, Object> getProperties() {
                return new HashMap<>();
            }

            @Override
            public Object getProperty(String key) {
                return null;
            }

            @Override
            public Object getProperty(String key, Object defaultValue) {
                return null;
            }

            @Override
            public String getDatabaseUrl() {
                return null;
            }

            @Override
            public String getUsername() {
                return null;
            }

            @Override
            public String getPassword() {
                return null;
            }

            @Override
            public List<String> getTableIncludes() {
                return null;
            }

            @Override
            public List<String> getTableExcludes() {
                return null;
            }

            @Override
            public long getPollingIntervalMs() {
                return 0;
            }

            @Override
            public int getBatchSize() {
                return 0;
            }

            @Override
            public boolean isAutoStart() {
                return false;
            }

            @Override
            public boolean isSnapshotEnabled() {
                return false;
            }

            @Override
            public String getSnapshotMode() {
                return null;
            }

            @Override
            public void validate() throws IllegalArgumentException {
            }
        };
    }
}
