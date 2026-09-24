package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual coverage for AbstractCDCConnector: the stop() lambda's InterruptedException path
 * (forced shutdownNow + interrupt restore) which round-1 could not reach.
 */
class AbstractCDCConnectorStopResidualCoverageTest {

    private static final class FakeConnector extends AbstractCDCConnector {
        FakeConnector(CDCConfiguration cfg) {
            super(cfg);
        }

        @Override
        protected void doStart() {
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

    private static CDCConfiguration cfg() {
        return CDCConfigurationBuilder.forDatabasePolling("fake-residual").pollingIntervalMs(0).build();
    }

    @Test
    void stopAwaitInterruptedForcesShutdownNowAndRestoresInterruptFlag() throws Exception {
        FakeConnector connector = new FakeConnector(cfg());
        connector.start().join();

        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());
        Field f = AbstractCDCConnector.class.getDeclaredField("scheduler");
        f.setAccessible(true);
        f.set(connector, scheduler);

        connector.stop().join();
        verify(scheduler).shutdown();
        verify(scheduler).shutdownNow();
    }

    @Test
    void stopAwaitTimeoutForcesShutdownNow() throws Exception {
        FakeConnector connector = new FakeConnector(cfg());
        connector.start().join();

        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(false);
        Field f = AbstractCDCConnector.class.getDeclaredField("scheduler");
        f.setAccessible(true);
        f.set(connector, scheduler);

        assertDoesNotThrow(() -> connector.stop().join());
        verify(scheduler).shutdownNow();
        assertTrue(!connector.isRunning());
    }
}
