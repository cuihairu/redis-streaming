package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CDC-M4 regression: a FAILED start or stop must not leak the resources the connector
 * already created.
 *
 * <ul>
 *   <li>{@code stop()} ran {@code doStop()} before the scheduler shutdown block, so a
 *       throwing {@code doStop()} skipped the shutdown and leaked the scheduler's
 *       non-daemon pool thread (the JVM cannot exit while it lives).</li>
 *   <li>{@code start()} never cleaned up when {@code doStart()} threw after
 *       {@code startScheduledPolling()}: the scheduler kept ticking against a connector
 *       that reports {@code running == false}.</li>
 *   <li>{@code DatabasePollingCDCConnector.doStart()} created the Hikari pool before the
 *       baseline query; a failed baseline left the pool (and its connections) open
 *       forever.</li>
 * </ul>
 *
 * <p>Old code fails all three assertions below with the leak present; the new code passes
 * them. Both codes still surface the original start/stop failure to the caller.</p>
 */
class CDCFailurePathLeakTest {

    /** Created connectors are cleaned up after each test so a leaking scheduler cannot pin the JVM. */
    private AbstractCDCConnector connector;

    @AfterEach
    void shutDownAnySchedulerLeftBehind() {
        if (connector != null && connector.scheduler != null) {
            connector.scheduler.shutdownNow();
        }
    }

    private static final class FailingStopConnector extends AbstractCDCConnector {
        FailingStopConnector(CDCConfiguration cfg) {
            super(cfg);
        }

        @Override
        protected void doStart() {
            startScheduledPolling();
        }

        @Override
        protected void doStop() {
            throw new IllegalStateException("stop failed");
        }

        @Override
        protected List<ChangeEvent> doPoll() {
            return List.of();
        }

        @Override
        protected void doCommit(String position) {
        }

        @Override
        protected void doResetToPosition(String position) {
        }
    }

    private static final class FailingStartConnector extends AbstractCDCConnector {
        FailingStartConnector(CDCConfiguration cfg) {
            super(cfg);
        }

        @Override
        protected void doStart() {
            startScheduledPolling();
            throw new IllegalStateException("start failed");
        }

        @Override
        protected void doStop() {
        }

        @Override
        protected List<ChangeEvent> doPoll() {
            return List.of();
        }

        @Override
        protected void doCommit(String position) {
        }

        @Override
        protected void doResetToPosition(String position) {
        }
    }

    private static CDCConfiguration cfg(long pollingIntervalMs) {
        return CDCConfigurationBuilder.forDatabasePolling("leak-probe")
                .pollingIntervalMs(pollingIntervalMs)
                .build();
    }

    @Test
    void failedStopStillShutsDownThePollingScheduler() {
        connector = new FailingStopConnector(cfg(25));
        connector.start().join();
        assertNotNull(connector.scheduler, "the polling scheduler must exist before stop");
        assertFalse(connector.scheduler.isShutdown(), "precondition: the scheduler is live");

        CompletionException ex = assertThrows(CompletionException.class, () -> connector.stop().join());
        assertTrue(ex.getCause().getCause().getMessage().contains("stop failed"),
                "the stop failure must still surface to the caller");

        assertTrue(connector.scheduler.isShutdown(),
                "CDC-M4: a throwing doStop() must not skip the scheduler shutdown — the leaked"
                        + " non-daemon pool thread keeps the JVM alive");
        assertFalse(connector.isRunning());
    }

    @Test
    void failedStartShutsDownTheSchedulerItCreated() {
        connector = new FailingStartConnector(cfg(25));

        CompletionException ex = assertThrows(CompletionException.class, () -> connector.start().join());
        assertTrue(ex.getCause().getCause().getMessage().contains("start failed"),
                "the start failure must still surface to the caller");
        assertNotNull(connector.scheduler, "doStart created the scheduler before failing");

        assertTrue(connector.scheduler.isShutdown(),
                "CDC-M4: a scheduler created by a FAILED start must be shut down instead of"
                        + " ticking forever against a stopped connector");
        assertFalse(connector.isRunning());
    }

    @Test
    void failedStartClosesThePoolItCreated() throws Exception {
        String db = "jdbc:h2:mem:leak" + UUID.randomUUID().toString().substring(0, 8);
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("h2-leak")
                .username("sa").password("")
                .pollingIntervalMs(0)
                .property("jdbc.url", db)
                .property("driver.class", "org.h2.Driver")
                .property("tables", "no_such_table")
                .property("timestamp.column", "updated_at")
                .build();
        connector = new DatabasePollingCDCConnector(cfg);

        CompletionException ex = assertThrows(CompletionException.class, () -> connector.start().join());
        assertTrue(ex.getCause().getCause() instanceof SQLException,
                "start must fail on the baseline query against a missing table, not earlier: " + ex);

        Field f = DatabasePollingCDCConnector.class.getDeclaredField("dataSource");
        f.setAccessible(true);
        DataSource dataSource = (DataSource) f.get(connector);
        assertNotNull(dataSource, "the pool was created before the failure");
        assertTrue(((com.zaxxer.hikari.HikariDataSource) dataSource).isClosed(),
                "CDC-M4: a start failure after the pool was created must close the pool");
    }
}
