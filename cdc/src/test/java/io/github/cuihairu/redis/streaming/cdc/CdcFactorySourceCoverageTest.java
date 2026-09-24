package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers CDCConnectorFactory dispatch, CDCSource#run drain loop and config accessors. */
class CdcFactorySourceCoverageTest {

    @Test
    void factoryCreatesAllConnectorTypes() {
        CDCConfiguration pollingCfg = CDCConfigurationBuilder.forDatabasePolling("f-poll")
                .jdbcUrl("jdbc:noop").tables("t").pollingIntervalMs(0).build();
        assertTrue(CDCConnectorFactory.create(CDCConnectorFactory.ConnectorType.DATABASE_POLLING, pollingCfg)
                instanceof io.github.cuihairu.redis.streaming.cdc.impl.DatabasePollingCDCConnector);

        CDCConfiguration mysqlCfg = CDCConfigurationBuilder.forMySQLBinlog("f-mysql").build();
        assertNotNull(CDCConnectorFactory.create(CDCConnectorFactory.ConnectorType.MYSQL_BINLOG, mysqlCfg));
        CDCConfiguration pgCfg = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("f-pg").build();
        assertNotNull(CDCConnectorFactory.create(CDCConnectorFactory.ConnectorType.POSTGRESQL_LOGICAL_REPLICATION, pgCfg));

        assertNotNull(CDCConnectorFactory.create("database_polling", pollingCfg));
        assertNotNull(CDCConnectorFactory.createMySQLBinlog(mysqlCfg));
        assertNotNull(CDCConnectorFactory.createPostgreSQLLogicalReplication(pgCfg));
        assertNotNull(CDCConnectorFactory.createDatabasePolling(pollingCfg));
        assertThrows(IllegalArgumentException.class,
                () -> CDCConnectorFactory.create("nope", pollingCfg));
    }

    @Test
    void sourceRunDrainsEventsThenIdlesOut() throws Exception {
        CDCConnector connector = mock(CDCConnector.class);
        when(connector.isRunning()).thenReturn(false, true);
        when(connector.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(connector.stop()).thenReturn(CompletableFuture.completedFuture(null));
        ChangeEvent first = new ChangeEvent(ChangeEvent.EventType.INSERT, "db", "t", Map.of());
        when(connector.poll()).thenReturn(List.of(first), List.of(), List.of(), List.of());

        CDCSource source = new CDCSource(connector, 0, 3);
        List<ChangeEvent> out = new ArrayList<>();
        AtomicBoolean stopped = new AtomicBoolean(false);
        source.run(new io.github.cuihairu.redis.streaming.api.stream.StreamSource.SourceContext<>() {
            @Override
            public void collect(ChangeEvent element) {
                out.add(element);
            }
            @Override
            public void collectWithTimestamp(ChangeEvent element, long timestamp) {
                out.add(element);
            }
            @Override
            public Object getCheckpointLock() {
                return this;
            }
            @Override
            public boolean isStopped() {
                return stopped.get();
            }
        });
        verify(connector).start();
        assertEquals(List.of(first), out);

        source.cancel();
        verify(connector).stop();
    }

    @Test
    void sourceRunSkipsNullEvents() throws Exception {
        CDCConnector connector = mock(CDCConnector.class);
        when(connector.isRunning()).thenReturn(true);
        List<ChangeEvent> withNulls = new ArrayList<>();
        withNulls.add(null);
        withNulls.add(null);
        when(connector.poll()).thenReturn(withNulls, List.of(), List.of());

        CDCSource source = new CDCSource(connector, 0, 2);
        List<ChangeEvent> out = new ArrayList<>();
        AtomicBoolean stopped = new AtomicBoolean(false);
        source.run(new io.github.cuihairu.redis.streaming.api.stream.StreamSource.SourceContext<>() {
            @Override
            public void collect(ChangeEvent element) {
                out.add(element);
            }
            @Override
            public void collectWithTimestamp(ChangeEvent element, long timestamp) {
                out.add(element);
            }
            @Override
            public Object getCheckpointLock() {
                return this;
            }
            @Override
            public boolean isStopped() {
                return stopped.get();
            }
        });
        assertTrue(out.isEmpty());
    }

    @Test
    void configurationAccessorsAndValidate() {
        CDCConfigurationBuilder builder = CDCConfigurationBuilder.forDatabasePolling("cfg-1")
                .username("u").password("p")
                .jdbcUrl("jdbc:h2:mem:x")
                .property("database.url", "jdbc:h2:mem:y")
                .property("table.includes", java.util.Arrays.asList("a", null, 3, "b"))
                .property("table.excludes", java.util.Arrays.asList(null, "x"))
                .property("type", "polling");
        CDCConfiguration config = builder.build();

        config.validate();
        assertEquals("jdbc:h2:mem:y", config.getDatabaseUrl());
        assertEquals(List.of("a", "b"), config.getTableIncludes());
        assertEquals(List.of("x"), config.getTableExcludes());
        assertEquals("polling", config.getType());
        assertEquals("u", config.getUsername());
        assertTrue(config.getBatchSize() > 0);
        assertNotNull(config.getProperties());
        assertNotNull(config.getProperty("type"));
        assertEquals("def", config.getProperty("missing", "def"));
        config.isAutoStart();
        config.isSnapshotEnabled();
        config.getSnapshotMode();
        assertTrue(config.getPollingIntervalMs() > 0);
    }

    @Test
    void emptyTableFilterListsYieldEmpty() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("cfg-2").build();
        assertTrue(config.getTableIncludes().isEmpty());
        assertTrue(config.getTableExcludes().isEmpty());
        assertTrue(config.getDatabaseUrl() == null);
    }
}
