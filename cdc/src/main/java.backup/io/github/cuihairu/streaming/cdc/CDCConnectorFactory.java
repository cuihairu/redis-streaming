package io.github.cuihairu.redis.streaming.cdc;

import io.github.cuihairu.redis.streaming.cdc.impl.DatabasePollingCDCConnector;
import io.github.cuihairu.redis.streaming.cdc.impl.MySQLBinlogCDCConnector;
import io.github.cuihairu.redis.streaming.cdc.impl.PostgreSQLLogicalReplicationCDCConnector;

/**
 * Factory for creating CDC connectors
 */
public class CDCConnectorFactory {

    public enum ConnectorType {
        MYSQL_BINLOG,
        POSTGRESQL_LOGICAL_REPLICATION,
        DATABASE_POLLING
    }

    /**
     * Create a CDC connector based on type and configuration
     *
     * @param type the connector type
     * @param configuration the configuration
     * @return the CDC connector
     */
    public static CDCConnector create(ConnectorType type, CDCConfiguration configuration) {
        switch (type) {
            case MYSQL_BINLOG:
                return new MySQLBinlogCDCConnector(configuration);
            case POSTGRESQL_LOGICAL_REPLICATION:
                return new PostgreSQLLogicalReplicationCDCConnector(configuration);
            case DATABASE_POLLING:
                return new DatabasePollingCDCConnector(configuration);
            default:
                throw new IllegalArgumentException("Unsupported connector type: " + type);
        }
    }

    /**
     * Create a CDC connector based on type name and configuration
     *
     * @param typeName the connector type name
     * @param configuration the configuration
     * @return the CDC connector
     */
    public static CDCConnector create(String typeName, CDCConfiguration configuration) {
        try {
            ConnectorType type = ConnectorType.valueOf(typeName.toUpperCase());
            return create(type, configuration);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown connector type: " + typeName, e);
        }
    }

    /**
     * Create a MySQL binlog CDC connector
     *
     * @param configuration the configuration
     * @return the MySQL binlog CDC connector
     */
    public static CDCConnector createMySQLBinlog(CDCConfiguration configuration) {
        return new MySQLBinlogCDCConnector(configuration);
    }

    /**
     * Create a PostgreSQL logical replication CDC connector
     *
     * @param configuration the configuration
     * @return the PostgreSQL logical replication CDC connector
     */
    public static CDCConnector createPostgreSQLLogicalReplication(CDCConfiguration configuration) {
        return new PostgreSQLLogicalReplicationCDCConnector(configuration);
    }

    /**
     * Create a database polling CDC connector
     *
     * @param configuration the configuration
     * @return the database polling CDC connector
     */
    public static CDCConnector createDatabasePolling(CDCConfiguration configuration) {
        return new DatabasePollingCDCConnector(configuration);
    }
}