package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.CDCManager;
import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Error-path storms for the CDC connectors and manager. start/connect methods are skipped so
 * the storm never attempts real network I/O; getters, filters, lifecycle-reporting and
 * failure handling are all exercised.
 */
class CdcConnectorStormTest {

    @Test
    void managerAndConnectorsStormWithoutConnecting() {
        CDCManager manager = new CDCManager();
        assertTrue(Storms.storm(manager, null, "start") >= 2);

        MySQLBinlogCDCConnector mysql = Storms.constructing(() -> new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog("mysql-storm")
                        .mysqlHostname("127.0.0.1").mysqlPort(1).username("u").password("p").build()));
        assertTrue(Storms.storm(mysql, null, "start", "stop", "poll") > 3);

        DatabasePollingCDCConnector polling = Storms.constructing(() -> new DatabasePollingCDCConnector(
                CDCConfigurationBuilder.forDatabasePolling("poll-storm")
                        .jdbcUrl("jdbc:nonexistent:stub").username("u").password("p").build()));
        assertTrue(Storms.storm(polling, null, "start", "stop") > 3);

        PostgreSQLLogicalReplicationCDCConnector pg = Storms.constructing(
                () -> new PostgreSQLLogicalReplicationCDCConnector(
                        CDCConfigurationBuilder.forPostgreSQLLogicalReplication("pg-storm")
                                .postgresqlHostname("127.0.0.1").postgresqlPort(1).postgresqlDatabase("d")
                                .username("u").password("p").build()));
        assertTrue(Storms.storm(pg, null, "start", "stop", "poll") > 3);
    }
}
