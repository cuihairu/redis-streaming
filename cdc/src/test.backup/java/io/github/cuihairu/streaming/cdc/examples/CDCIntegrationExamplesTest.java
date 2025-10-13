package io.github.cuihairu.redis.streaming.cdc.examples;

import io.github.cuihairu.redis.streaming.cdc.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Integration test examples for CDC connectors
 * These tests are disabled by default as they require external database setup
 */
@Slf4j
@Tag("integration")
@Disabled("Integration tests require external database setup")
class CDCIntegrationExamplesTest {

    @Test
    void testMySQLBinlogCDCExample() throws InterruptedException {
        // Configure MySQL binlog CDC connector
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("mysql_example")
                .username("mysql_user")
                .password("mysql_password")
                .mysqlHostname("localhost")
                .mysqlPort(3306)
                .mysqlServerId(1001)
                .batchSize(10)
                .pollingIntervalMs(1000)
                .build();

        CDCConnector connector = CDCConnectorFactory.createMySQLBinlog(config);

        // Set up event listener
        CountDownLatch eventLatch = new CountDownLatch(5);
        connector.setEventListener(new CDCEventListener() {
            @Override
            public void onEventsCapture(String connectorName, int eventCount) {
                log.info("Captured {} events from connector: {}", eventCount, connectorName);
                for (int i = 0; i < eventCount; i++) {
                    eventLatch.countDown();
                }
            }

            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                log.error("Connector error: {}", connectorName, error);
            }
        });

        try {
            // Start connector
            CompletableFuture<Void> startFuture = connector.start();
            startFuture.join();

            log.info("MySQL binlog CDC connector started successfully");

            // Poll for events
            for (int i = 0; i < 10; i++) {
                List<ChangeEvent> events = connector.poll();
                if (!events.isEmpty()) {
                    log.info("Polled {} events", events.size());
                    events.forEach(event -> {
                        log.info("Event: {} {} {}.{} key={}",
                                event.getEventType(),
                                event.getSource(),
                                event.getDatabase(),
                                event.getTable(),
                                event.getKey());
                    });

                    // Commit position
                    String position = connector.getCurrentPosition();
                    if (position != null) {
                        connector.commit(position);
                        log.info("Committed position: {}", position);
                    }
                }

                Thread.sleep(2000);
            }

            // Wait for some events or timeout
            boolean receivedEvents = eventLatch.await(30, TimeUnit.SECONDS);
            log.info("Received events: {}", receivedEvents);

        } finally {
            // Stop connector
            CompletableFuture<Void> stopFuture = connector.stop();
            stopFuture.join();
            log.info("MySQL binlog CDC connector stopped");
        }
    }

    @Test
    void testPostgreSQLLogicalReplicationExample() throws InterruptedException {
        // Configure PostgreSQL logical replication CDC connector
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("pg_example")
                .username("postgres_user")
                .password("postgres_password")
                .postgresqlHostname("localhost")
                .postgresqlPort(5432)
                .postgresqlDatabase("test_db")
                .postgresqlSlotName("cdc_slot")
                .postgresqlPublicationName("cdc_publication")
                .postgresqlStatusInterval(10000)
                .batchSize(10)
                .build();

        CDCConnector connector = CDCConnectorFactory.createPostgreSQLLogicalReplication(config);

        // Set up event listener
        connector.setEventListener(new CDCEventListener() {
            @Override
            public void onEventsCapture(String connectorName, int eventCount) {
                log.info("PostgreSQL captured {} events from connector: {}", eventCount, connectorName);
            }

            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                log.error("PostgreSQL connector error: {}", connectorName, error);
            }
        });

        try {
            // Start connector
            connector.start().join();
            log.info("PostgreSQL logical replication CDC connector started");

            // Poll for events
            for (int i = 0; i < 5; i++) {
                List<ChangeEvent> events = connector.poll();
                log.info("PostgreSQL polled {} events", events.size());

                if (!events.isEmpty()) {
                    String position = connector.getCurrentPosition();
                    if (position != null) {
                        connector.commit(position);
                    }
                }

                Thread.sleep(3000);
            }

        } finally {
            connector.stop().join();
            log.info("PostgreSQL CDC connector stopped");
        }
    }

    @Test
    void testDatabasePollingCDCExample() throws InterruptedException {
        // Configure database polling CDC connector
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("polling_example")
                .username("db_user")
                .password("db_password")
                .jdbcUrl("jdbc:mysql://localhost:3306/test_db")
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("users,orders,products")
                .timestampColumn("updated_at")
                .incrementalColumn("id")
                .queryTimeout(30)
                .batchSize(20)
                .pollingIntervalMs(5000)
                .build();

        CDCConnector connector = CDCConnectorFactory.createDatabasePolling(config);

        // Set up event listener
        connector.setEventListener(new CDCEventListener() {
            @Override
            public void onEventsCapture(String connectorName, int eventCount) {
                log.info("Database polling captured {} events from connector: {}", eventCount, connectorName);
            }

            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                log.error("Database polling connector error: {}", connectorName, error);
            }
        });

        try {
            // Start connector
            connector.start().join();
            log.info("Database polling CDC connector started");

            // Poll for events
            for (int i = 0; i < 3; i++) {
                List<ChangeEvent> events = connector.poll();
                log.info("Database polling polled {} events", events.size());

                if (!events.isEmpty()) {
                    String position = connector.getCurrentPosition();
                    if (position != null) {
                        connector.commit(position);
                    }
                }

                Thread.sleep(10000);
            }

        } finally {
            connector.stop().join();
            log.info("Database polling CDC connector stopped");
        }
    }

    @Test
    void testCDCManagerExample() throws InterruptedException {
        CDCManager manager = new CDCManager();

        try {
            // Create multiple connectors
            CDCConfiguration mysqlConfig = CDCConfigurationBuilder.forMySQLBinlog("mysql_mgr")
                    .username("mysql_user")
                    .password("mysql_password")
                    .mysqlHostname("localhost")
                    .mysqlPort(3306)
                    .mysqlServerId(2001)
                    .build();

            CDCConfiguration pollingConfig = CDCConfigurationBuilder.forDatabasePolling("polling_mgr")
                    .username("db_user")
                    .password("db_password")
                    .jdbcUrl("jdbc:mysql://localhost:3306/test_db")
                    .tables("audit_log")
                    .timestampColumn("created_at")
                    .build();

            CDCConnector mysqlConnector = CDCConnectorFactory.create(
                    CDCConnectorFactory.ConnectorType.MYSQL_BINLOG, mysqlConfig);
            CDCConnector pollingConnector = CDCConnectorFactory.create(
                    CDCConnectorFactory.ConnectorType.DATABASE_POLLING, pollingConfig);

            // Add connectors to manager
            manager.addConnector(mysqlConnector);
            manager.addConnector(pollingConnector);

            log.info("Added {} connectors to manager", manager.getConnectorCount());

            // Start manager
            manager.start().join();
            log.info("CDC manager started with {} running connectors",
                    manager.getRunningConnectorCount());

            // Poll all connectors
            for (int i = 0; i < 3; i++) {
                Map<String, List<ChangeEvent>> allEvents = manager.pollAll();
                allEvents.forEach((name, events) -> {
                    log.info("Connector {} produced {} events", name, events.size());
                });

                // Get health status
                Map<String, CDCHealthStatus> healthStatus = manager.getHealthStatusAll();
                healthStatus.forEach((name, status) -> {
                    log.info("Connector {} health: {} - {}", name, status.getStatus(), status.getMessage());
                });

                // Get metrics
                Map<String, CDCMetrics> metrics = manager.getMetricsAll();
                metrics.forEach((name, metric) -> {
                    log.info("Connector {} metrics: {} total events, {} errors",
                            name, metric.getTotalEventsCaptured(), metric.getErrorsCount());
                });

                Thread.sleep(5000);
            }

        } finally {
            // Stop manager
            manager.stop().join();
            log.info("CDC manager stopped");
        }
    }
}