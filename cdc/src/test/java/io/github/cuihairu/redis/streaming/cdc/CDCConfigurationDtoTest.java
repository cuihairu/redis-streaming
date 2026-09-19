package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Coverage for the builder + configuration DTO surface. */
class CDCConfigurationDtoTest {

    @Test
    void builderRoundTripsAllFields() {
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("dto")
                .username("u")
                .password("p")
                .batchSize(7)
                .pollingIntervalMs(1234)
                .tables(List.of("t1", "t2"))
                .queryTimeout(9)
                .driverClass("org.h2.Driver")
                .jdbcUrl("jdbc:h2:mem:x")
                .incrementalColumn("id")
                .timestampColumn("ts")
                .property("custom", "cv")
                .build();

        assertEquals("dto", cfg.getName());
        assertEquals("u", cfg.getUsername());
        assertEquals("p", cfg.getPassword());
        assertEquals(7, cfg.getBatchSize());
        assertEquals(1234, cfg.getPollingIntervalMs());
        assertEquals("jdbc:h2:mem:x", cfg.getDatabaseUrl());
        assertEquals("org.h2.Driver", cfg.getDriverClass());
        assertNotNull(cfg.getType());
        assertNotNull(cfg.getSnapshotMode());
        assertEquals("cv", cfg.getProperties().get("custom"));
        assertEquals("dflt", cfg.getProperty("missing", "dflt"));
        assertTrue(cfg.isAutoStart());
        assertTrue(cfg.isSnapshotEnabled());
        assertNotNull(cfg.getTableIncludes());
        assertNotNull(cfg.getTableExcludes());
    }

    @Test
    void mysqlAndPostgresFactoriesExposeDefaults() {
        CDCConfiguration mysql = CDCConfigurationBuilder.forMySQLBinlog("m")
                .username("u").password("p").mysqlHostname("h").mysqlPort(3306)
                .mysqlServerId(42).mysqlBinlogFilename("bin.001").mysqlBinlogPosition(4L)
                .build();
        assertEquals("m", mysql.getName());
        assertEquals(42, mysql.getProperties().get("mysql.server.id"));

        CDCConfiguration pg = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("pg")
                .username("u").password("p")
                .postgresqlHostname("h").postgresqlPort(5432).postgresqlDatabase("d")
                .postgresqlSlotName("slot").postgresqlPublicationName("pub")
                .postgresqlStatusInterval(2000L)
                .build();
        assertEquals("slot", pg.getProperties().get("postgresql.slot.name"));
        assertNotNull(pg.getSnapshotMode());
    }

    @Test
    void snapshotModeAndAutoStartToggles() {
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("toggle")
                .username("u").password("p")
                .build();
        assertNotNull(cfg.getSnapshotMode());
        assertTrue(cfg.isAutoStart());
    }
}
