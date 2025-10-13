package io.github.cuihairu.redis.streaming.sink.impl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.cuihairu.redis.streaming.sink.*;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Database sink connector for writing data to SQL databases
 */
@Slf4j
public class DatabaseSinkConnector extends AbstractSinkConnector {

    private static final String JDBC_URL_PROPERTY = "jdbc.url";
    private static final String USERNAME_PROPERTY = "username";
    private static final String PASSWORD_PROPERTY = "password";
    private static final String TABLE_PROPERTY = "table";
    private static final String INSERT_SQL_PROPERTY = "insert.sql";
    private static final String AUTO_CREATE_TABLE_PROPERTY = "auto.create.table";
    private static final String DRIVER_CLASS_PROPERTY = "driver.class";

    private DataSource dataSource;
    private String tableName;
    private String insertSql;
    private boolean autoCreateTable;

    public DatabaseSinkConnector(SinkConfiguration configuration) {
        super(configuration);
    }

    @Override
    protected void doStart() throws Exception {
        // Validate configuration
        configuration.validate();

        // Parse configuration
        String jdbcUrl = (String) configuration.getProperty(JDBC_URL_PROPERTY);
        String username = (String) configuration.getProperty(USERNAME_PROPERTY);
        String password = (String) configuration.getProperty(PASSWORD_PROPERTY);
        String driverClass = (String) configuration.getProperty(DRIVER_CLASS_PROPERTY);
        this.tableName = (String) configuration.getProperty(TABLE_PROPERTY, "streaming_data");
        this.insertSql = (String) configuration.getProperty(INSERT_SQL_PROPERTY);
        this.autoCreateTable = Boolean.parseBoolean(
                String.valueOf(configuration.getProperty(AUTO_CREATE_TABLE_PROPERTY, "false")));

        // Create data source
        createDataSource(jdbcUrl, username, password, driverClass);

        // Create table if configured
        if (autoCreateTable) {
            createTableIfNotExists();
        }

        // Prepare insert SQL if not provided
        if (insertSql == null) {
            prepareDefaultInsertSql();
        }

        log.info("Database sink connector initialized with URL: {}, table: {}", jdbcUrl, tableName);
    }

    @Override
    protected void doStop() throws Exception {
        if (dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
        }
    }

    @Override
    protected SinkResult doWrite(Collection<SinkRecord> records) throws Exception {
        if (records.isEmpty()) {
            return SinkResult.success(0, "No records to write");
        }

        List<SinkError> errors = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement statement = connection.prepareStatement(insertSql)) {

                for (SinkRecord record : records) {
                    try {
                        prepareStatement(statement, record);
                        statement.addBatch();
                    } catch (Exception e) {
                        failureCount++;
                        errors.add(new SinkError(record, "Error preparing statement: " + e.getMessage(), e));
                    }
                }

                // Execute batch
                int[] batchResults = statement.executeBatch();
                connection.commit();

                // Process results
                int index = 0;
                for (SinkRecord record : records) {
                    if (index < batchResults.length) {
                        if (batchResults[index] >= 0) {
                            successCount++;
                        } else {
                            failureCount++;
                            errors.add(new SinkError(record, "Batch execution failed for record"));
                        }
                    }
                    index++;
                }

            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }

        } catch (SQLException e) {
            log.error("Database write error", e);
            return SinkResult.failure("Database write failed: " + e.getMessage());
        }

        if (failureCount == 0) {
            return SinkResult.success(successCount, "All records written successfully");
        } else if (successCount > 0) {
            return SinkResult.partialSuccess(successCount, failureCount, errors);
        } else {
            return SinkResult.failure(failureCount, "All records failed", errors);
        }
    }

    @Override
    protected void doFlush() throws Exception {
        // For databases, we don't need explicit flushing as transactions handle this
        log.debug("Flush requested for database connector: {}", getName());
    }

    private void createDataSource(String jdbcUrl, String username, String password, String driverClass) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);

        if (driverClass != null) {
            config.setDriverClassName(driverClass);
        }

        // Connection pool settings
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        this.dataSource = new HikariDataSource(config);
    }

    private void createTableIfNotExists() throws SQLException {
        String createTableSql = String.format(
                "CREATE TABLE IF NOT EXISTS %s (" +
                "    id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "    topic VARCHAR(255)," +
                "    record_key VARCHAR(255)," +
                "    record_value TEXT," +
                "    headers TEXT," +
                "    timestamp TIMESTAMP," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")", tableName);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute(createTableSql);
            log.info("Created table: {}", tableName);

        } catch (SQLException e) {
            log.warn("Could not create table (might already exist): {}", e.getMessage());
        }
    }

    private void prepareDefaultInsertSql() {
        this.insertSql = String.format(
                "INSERT INTO %s (topic, record_key, record_value, headers, timestamp) VALUES (?, ?, ?, ?, ?)",
                tableName);
    }

    private void prepareStatement(PreparedStatement statement, SinkRecord record) throws SQLException {
        statement.setString(1, record.getTopic());
        statement.setString(2, record.getKey());

        // Convert value to string
        String valueStr;
        if (record.getValue() instanceof String) {
            valueStr = (String) record.getValue();
        } else {
            // Convert to JSON string or toString
            valueStr = record.getValue() != null ? record.getValue().toString() : null;
        }
        statement.setString(3, valueStr);

        // Convert headers to string
        String headersStr = null;
        if (record.getHeaders() != null && !record.getHeaders().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : record.getHeaders().entrySet()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
            }
            headersStr = sb.toString();
        }
        statement.setString(4, headersStr);

        // Set timestamp
        Timestamp timestamp = record.getTimestamp() != null
                ? Timestamp.from(record.getTimestamp())
                : Timestamp.from(Instant.now());
        statement.setTimestamp(5, timestamp);
    }
}