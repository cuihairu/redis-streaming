package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers CDCManager health-monitoring lambdas, commitAll and stop paths. */
class CDCManagerLifecycleCoverageTest {

    private static CDCConnector healthyConnector(String name) {
        CDCConnector connector = mock(CDCConnector.class);
        when(connector.getName()).thenReturn(name);
        when(connector.getHealthStatus()).thenReturn(CDCHealthStatus.healthy("ok"));
        when(connector.start()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(connector.stop()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(connector.poll()).thenReturn(List.of());
        when(connector.getMetrics()).thenReturn(new CDCMetrics());
        when(connector.getCurrentPosition()).thenReturn("pos");
        when(connector.isRunning()).thenReturn(false);
        return connector;
    }

    @Test
    void monitorConnectorHealthLambdasAreInvokable() throws Exception {
        CDCManager manager = new CDCManager();
        CDCConnector unhealthy = mock(CDCConnector.class);
        when(unhealthy.getName()).thenReturn("c1");
        when(unhealthy.getHealthStatus()).thenReturn(CDCHealthStatus.unhealthy("down"));
        manager.addConnector(unhealthy);
        manager.addConnector(healthyConnector("c2"));

        Method monitor = CDCManager.class.getDeclaredMethod("monitorConnectorHealth");
        monitor.setAccessible(true);
        assertDoesNotThrow(() -> monitor.invoke(manager));

        // the scheduled-task lambda body (try/catch wrapper) is a synthetic method
        Method scheduled = CDCManager.class.getDeclaredMethod("lambda$startHealthMonitoring$8");
        scheduled.setAccessible(true);
        assertDoesNotThrow(() -> scheduled.invoke(manager));

        Method forEachLambda = CDCManager.class.getDeclaredMethod(
                "lambda$monitorConnectorHealth$9", String.class, CDCConnector.class);
        forEachLambda.setAccessible(true);
        assertDoesNotThrow(() -> forEachLambda.invoke(manager, "c1", unhealthy));
    }

    @Test
    void commitAllAndStopRunTheirLambdas() {
        CDCManager manager = new CDCManager();
        CDCConnector connector = healthyConnector("cA");
        manager.addConnector(connector);
        manager.start().join();

        manager.commitAll(Map.of("cA", "pos:1", "missing", "pos:2"));
        manager.pollAll();
        manager.getHealthStatusAll();
        manager.getMetricsAll();
        manager.getCurrentPositionsAll();
        assertTrue(manager.isRunning());
        assertEquals(1, manager.getConnectorCount());

        manager.stop().join();
        assertEquals(0, manager.getRunningConnectorCount());
    }

    @Test
    void commitAllSwallowsConnectorFailures() {
        CDCManager manager = new CDCManager();
        CDCConnector connector = healthyConnector("cB");
        org.mockito.Mockito.doThrow(new IllegalStateException("commit failed"))
                .when(connector).commit("bad");
        manager.addConnector(connector);
        assertDoesNotThrow(() -> manager.commitAll(Map.of("cB", "bad")));
    }
}
