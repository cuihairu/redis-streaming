package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

class CDCConfigurationBuilderTest {

    @Test
    void testBasicBuild() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .username("user")
                .password("pass")
                .batchSize(50)
                .pollingIntervalMs(2000)
                .build();

        assertEquals("test", config.getName());
        assertEquals("user", config.getUsername());
        assertEquals("pass", config.getPassword());
        assertEquals(50, config.getBatchSize());
        assertEquals(2000, config.getPollingIntervalMs());
    }

    @Test
    void testMySQLSpecificConfig() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("mysql_cdc")
                .username("mysql_user")
                .password("mysql_pass")
                .mysqlHostname("mysql-server")
                .mysqlPort(3306)
                .mysqlServerId(1001)
                .mysqlBinlogFilename("binlog.000001")
                .mysqlBinlogPosition(12345)
                .build();

        assertEquals("mysql_cdc", config.getName());
        assertEquals("mysql-server", config.getProperty("hostname"));
        assertEquals(3306, config.getProperty("port"));
        assertEquals(1001L, config.getProperty("server.id"));
        assertEquals("binlog.000001", config.getProperty("binlog.filename"));
        assertEquals(12345L, config.getProperty("binlog.position"));
    }

    @Test
    void testPostgreSQLSpecificConfig() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("pg_cdc")
                .username("pg_user")
                .password("pg_pass")
                .postgresqlHostname("pg-server")
                .postgresqlPort(5432)
                .postgresqlDatabase("test_db")
                .postgresqlSlotName("test_slot")
                .postgresqlPublicationName("test_pub")
                .postgresqlStatusInterval(5000)
                .build();

        assertEquals("pg_cdc", config.getName());
        assertEquals("pg-server", config.getProperty("hostname"));
        assertEquals(5432, config.getProperty("port"));
        assertEquals("test_db", config.getProperty("database"));
        assertEquals("test_slot", config.getProperty("slot.name"));
        assertEquals("test_pub", config.getProperty("publication.name"));
        assertEquals(5000L, config.getProperty("status.interval.ms"));
    }

    @Test
    void testDatabasePollingConfig() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("polling_cdc")
                .username("db_user")
                .password("db_pass")
                .jdbcUrl("jdbc:mysql://localhost:3306/test")
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("table1,table2,table3")
                .timestampColumn("modified_at")
                .incrementalColumn("id")
                .queryTimeout(60)
                .build();

        assertEquals("polling_cdc", config.getName());
        assertEquals("jdbc:mysql://localhost:3306/test", config.getProperty("jdbc.url"));
        assertEquals("com.mysql.cj.jdbc.Driver", config.getProperty("driver.class"));
        assertEquals("table1,table2,table3", config.getProperty("tables"));
        assertEquals("modified_at", config.getProperty("timestamp.column"));
        assertEquals("id", config.getProperty("incremental.column"));
        assertEquals(60, config.getProperty("query.timeout.seconds"));
    }

    @Test
    void testCustomProperties() {
        Map<String, Object> customProps = new HashMap<>();
        customProps.put("custom.prop1", "value1");
        customProps.put("custom.prop2", 123);

        CDCConfiguration config = new CDCConfigurationBuilder()
                .name("custom_cdc")
                .username("user")
                .password("pass")
                .property("single.prop", "single_value")
                .properties(customProps)
                .build();

        assertEquals("single_value", config.getProperty("single.prop"));
        assertEquals("value1", config.getProperty("custom.prop1"));
        assertEquals(123, config.getProperty("custom.prop2"));
    }

    @Test
    void testBuildWithoutName() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new CDCConfigurationBuilder()
                        .username("user")
                        .password("pass")
                        .build()
        );

        assertTrue(exception.getMessage().contains("name is required"));
    }

    @Test
    void testBuildWithEmptyName() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new CDCConfigurationBuilder()
                        .name("   ")
                        .username("user")
                        .password("pass")
                        .build()
        );

        assertTrue(exception.getMessage().contains("name is required"));
    }

    @Test
    void testDefaultValues() {
        CDCConfiguration config = new CDCConfigurationBuilder()
                .name("test")
                .build();

        assertEquals("test", config.getName());
        assertNull(config.getUsername());
        assertNull(config.getPassword());
        assertEquals(100, config.getBatchSize()); // Default batch size
        assertEquals(1000, config.getPollingIntervalMs()); // Default polling interval
    }

    @Test
    void testFactoryMethods() {
        CDCConfigurationBuilder mysqlBuilder = CDCConfigurationBuilder.forMySQLBinlog("mysql");
        CDCConfigurationBuilder pgBuilder = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("pg");
        CDCConfigurationBuilder pollingBuilder = CDCConfigurationBuilder.forDatabasePolling("polling");

        assertNotNull(mysqlBuilder);
        assertNotNull(pgBuilder);
        assertNotNull(pollingBuilder);

        assertEquals("mysql", mysqlBuilder.build().getName());
        assertEquals("pg", pgBuilder.build().getName());
        assertEquals("polling", pollingBuilder.build().getName());
    }
}