package io.github.cuihairu.redis.streaming.cdc;

import io.github.cuihairu.redis.streaming.cdc.impl.DatabasePollingCDCConnector;
import io.github.cuihairu.redis.streaming.cdc.impl.MySQLBinlogCDCConnector;
import io.github.cuihairu.redis.streaming.cdc.impl.PostgreSQLLogicalReplicationCDCConnector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CDCConnectorFactory
 */
class CDCConnectorFactoryTest {

    private final CDCConfiguration config = new CDCConfigurationBuilder()
            .name("test-connector")
            .username("user")
            .password("pass")
            .build();

    @Test
    void testCreateMySQLBinlogConnector() {
        CDCConnector connector = CDCConnectorFactory.create(
                CDCConnectorFactory.ConnectorType.MYSQL_BINLOG,
                config
        );

        assertNotNull(connector);
        assertTrue(connector instanceof MySQLBinlogCDCConnector);
    }

    @Test
    void testCreatePostgreSQLLogicalReplicationConnector() {
        CDCConnector connector = CDCConnectorFactory.create(
                CDCConnectorFactory.ConnectorType.POSTGRESQL_LOGICAL_REPLICATION,
                config
        );

        assertNotNull(connector);
        assertTrue(connector instanceof PostgreSQLLogicalReplicationCDCConnector);
    }

    @Test
    void testCreateDatabasePollingConnector() {
        CDCConnector connector = CDCConnectorFactory.create(
                CDCConnectorFactory.ConnectorType.DATABASE_POLLING,
                config
        );

        assertNotNull(connector);
        assertTrue(connector instanceof DatabasePollingCDCConnector);
    }

    @Test
    void testCreateMySQLBinlogConnectorByTypeName() {
        CDCConnector connector = CDCConnectorFactory.create("MYSQL_BINLOG", config);

        assertNotNull(connector);
        assertTrue(connector instanceof MySQLBinlogCDCConnector);
    }

    @Test
    void testCreatePostgreSQLLogicalReplicationConnectorByTypeName() {
        CDCConnector connector = CDCConnectorFactory.create("POSTGRESQL_LOGICAL_REPLICATION", config);

        assertNotNull(connector);
        assertTrue(connector instanceof PostgreSQLLogicalReplicationCDCConnector);
    }

    @Test
    void testCreateDatabasePollingConnectorByTypeName() {
        CDCConnector connector = CDCConnectorFactory.create("DATABASE_POLLING", config);

        assertNotNull(connector);
        assertTrue(connector instanceof DatabasePollingCDCConnector);
    }

    @Test
    void testCreateByTypeNameLowerCase() {
        CDCConnector connector = CDCConnectorFactory.create("mysql_binlog", config);

        assertNotNull(connector);
        assertTrue(connector instanceof MySQLBinlogCDCConnector);
    }

    @Test
    void testCreateByTypeNameMixedCase() {
        CDCConnector connector = CDCConnectorFactory.create("Mysql_Binlog", config);

        assertNotNull(connector);
        assertTrue(connector instanceof MySQLBinlogCDCConnector);
    }

    @Test
    void testCreateByInvalidTypeNameThrowsException() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CDCConnectorFactory.create("INVALID_TYPE", config)
        );

        assertTrue(exception.getMessage().contains("Unknown connector type"));
        assertTrue(exception.getMessage().contains("INVALID_TYPE"));
    }

    @Test
    void testCreateByEmptyTypeNameThrowsException() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CDCConnectorFactory.create("", config)
        );

        assertTrue(exception.getMessage().contains("Unknown connector type"));
    }

    @Test
    void testCreateMySQLBinlogViaConvenienceMethod() {
        CDCConnector connector = CDCConnectorFactory.createMySQLBinlog(config);

        assertNotNull(connector);
        assertTrue(connector instanceof MySQLBinlogCDCConnector);
    }

    @Test
    void testCreatePostgreSQLLogicalReplicationViaConvenienceMethod() {
        CDCConnector connector = CDCConnectorFactory.createPostgreSQLLogicalReplication(config);

        assertNotNull(connector);
        assertTrue(connector instanceof PostgreSQLLogicalReplicationCDCConnector);
    }

    @Test
    void testCreateDatabasePollingViaConvenienceMethod() {
        CDCConnector connector = CDCConnectorFactory.createDatabasePolling(config);

        assertNotNull(connector);
        assertTrue(connector instanceof DatabasePollingCDCConnector);
    }

    @Test
    void testConnectorTypeEnumContainsAllTypes() {
        CDCConnectorFactory.ConnectorType[] types = CDCConnectorFactory.ConnectorType.values();

        assertEquals(3, types.length);
        assertEquals(CDCConnectorFactory.ConnectorType.MYSQL_BINLOG, types[0]);
        assertEquals(CDCConnectorFactory.ConnectorType.POSTGRESQL_LOGICAL_REPLICATION, types[1]);
        assertEquals(CDCConnectorFactory.ConnectorType.DATABASE_POLLING, types[2]);
    }

    @Test
    void testValueOfConnectorTypeEnum() {
        assertEquals(
                CDCConnectorFactory.ConnectorType.MYSQL_BINLOG,
                CDCConnectorFactory.ConnectorType.valueOf("MYSQL_BINLOG")
        );
        assertEquals(
                CDCConnectorFactory.ConnectorType.POSTGRESQL_LOGICAL_REPLICATION,
                CDCConnectorFactory.ConnectorType.valueOf("POSTGRESQL_LOGICAL_REPLICATION")
        );
        assertEquals(
                CDCConnectorFactory.ConnectorType.DATABASE_POLLING,
                CDCConnectorFactory.ConnectorType.valueOf("DATABASE_POLLING")
        );
    }

    @Test
    void testValueOfConnectorTypeEnumCaseSensitive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CDCConnectorFactory.ConnectorType.valueOf("mysql_binlog")
        );
    }

    @Test
    void testCreateConnectorWithNullConfig() {
        // Connector classes may handle null config differently
        // MySQLBinlogCDCConnector constructor accepts null config
        CDCConnector connector = CDCConnectorFactory.createMySQLBinlog(null);
        assertNotNull(connector);
    }

    @Test
    void testCreateMultipleConnectors() {
        CDCConnector mysqlConnector = CDCConnectorFactory.createMySQLBinlog(config);
        CDCConnector postgresConnector = CDCConnectorFactory.createPostgreSQLLogicalReplication(config);
        CDCConnector pollingConnector = CDCConnectorFactory.createDatabasePolling(config);

        assertNotNull(mysqlConnector);
        assertNotNull(postgresConnector);
        assertNotNull(pollingConnector);

        assertTrue(mysqlConnector instanceof MySQLBinlogCDCConnector);
        assertTrue(postgresConnector instanceof PostgreSQLLogicalReplicationCDCConnector);
        assertTrue(pollingConnector instanceof DatabasePollingCDCConnector);
    }

    @Test
    void testCreateConnectorTypeNameWithWhitespace() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CDCConnectorFactory.create("MYSQL_BINLOG ", config)
        );

        assertTrue(exception.getMessage().contains("Unknown connector type"));
    }

    @Test
    void testAllConnectorTypesAreDistinct() {
        CDCConnectorFactory.ConnectorType mysql = CDCConnectorFactory.ConnectorType.MYSQL_BINLOG;
        CDCConnectorFactory.ConnectorType postgres = CDCConnectorFactory.ConnectorType.POSTGRESQL_LOGICAL_REPLICATION;
        CDCConnectorFactory.ConnectorType polling = CDCConnectorFactory.ConnectorType.DATABASE_POLLING;

        assertNotEquals(mysql, postgres);
        assertNotEquals(mysql, polling);
        assertNotEquals(postgres, polling);
    }

    @Test
    void testFactoryReturnsNewInstanceEachCall() {
        CDCConnector connector1 = CDCConnectorFactory.createMySQLBinlog(config);
        CDCConnector connector2 = CDCConnectorFactory.createMySQLBinlog(config);

        assertNotNull(connector1);
        assertNotNull(connector2);
        assertNotSame(connector1, connector2);
    }
}
