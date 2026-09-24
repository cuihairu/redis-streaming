package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.atLeastOnce;

/**
 * Residual coverage for CDCManager: the stop() scheduler shutdown lambda (forced shutdownNow
 * and interrupt paths) and the health-monitoring lambda failure swallow.
 */
class CDCManagerResidualCoverageTest {

    private static void setScheduler(CDCManager manager, ScheduledExecutorService scheduler) throws Exception {
        Field f = CDCManager.class.getDeclaredField("scheduler");
        f.setAccessible(true);
        f.set(manager, scheduler);
    }

    private static CDCConnector connector(String name) {
        CDCConnector c = mock(CDCConnector.class);
        when(c.getName()).thenReturn(name);
        when(c.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(c.stop()).thenReturn(CompletableFuture.completedFuture(null));
        when(c.isRunning()).thenReturn(true);
        return c;
    }

    @Test
    void stopForcesShutdownNowWhenTerminationTimesOut() throws Exception {
        CDCManager manager = new CDCManager();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        setScheduler(manager, scheduler);
        manager.addConnector(connector("c1"));
        manager.start().join();
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(false);
        manager.stop().join();
        verify(scheduler).shutdown();
        verify(scheduler).shutdownNow();
    }

    @Test
    void stopReinterruptsWhenAwaitIsInterrupted() throws Exception {
        CDCManager manager = new CDCManager();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        setScheduler(manager, scheduler);
        manager.addConnector(connector("c2"));
        manager.start().join();
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());
        manager.stop().join();
        verify(scheduler).shutdownNow();
    }

    @Test
    void healthMonitoringLambdaSwallowsMonitorFailures() throws Exception {
        CDCManager manager = new CDCManager();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        setScheduler(manager, scheduler);
        CDCConnector bad = connector("c3");
        when(bad.getHealthStatus()).thenThrow(new IllegalStateException("health boom"));
        manager.addConnector(bad);
        manager.start().join();

        org.mockito.ArgumentCaptor<Runnable> task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(task.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        assertDoesNotThrow(() -> task.getValue().run());

        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        manager.stop().join();
    }

    @Test
    void commitAllRunsScheduledHooksWhenConnectorListEmpty() throws Exception {
        CDCManager manager = new CDCManager();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        setScheduler(manager, scheduler);
        manager.start().join();
        verify(scheduler, atLeastOnce()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any(TimeUnit.class));
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        manager.stop().join();
    }
}
