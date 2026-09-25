package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.core.utils.SystemUtils;
import io.github.cuihairu.redis.streaming.registry.metrics.ApplicationMetricCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Covers the defensive fallback branches of {@link ApplicationMetricCollector#collectMetric()}:
 * ProcessHandle failure falls back to parsing the JVM runtime name, malformed names map the pid
 * to -1, and a failing local-host lookup keeps the {@code unknown} hostname.
 */
class ApplicationMetricCollectorFallbackTest {

    @AfterEach
    void resetHostnameCache() {
        SystemUtils.clearHostnameCache();
    }

    private static long pid(Map<String, Object> metrics) {
        Object pid = metrics.get("pid");
        assertNotNull(pid, "pid must be published");
        return ((Number) pid).longValue();
    }

    @Test
    void pidFallsBackToRuntimeNameParsingWhenProcessHandleUnavailable() {
        RuntimeMXBean runtime = mock(RuntimeMXBean.class);
        when(runtime.getName()).thenReturn("4242@test-host");

        try (MockedStatic<ProcessHandle> processHandle = mockStatic(ProcessHandle.class);
             MockedStatic<ManagementFactory> management = mockStatic(ManagementFactory.class)) {
            processHandle.when(ProcessHandle::current)
                    .thenThrow(new UnsupportedOperationException("ProcessHandle unavailable"));
            management.when(ManagementFactory::getRuntimeMXBean).thenReturn(runtime);
            management.when(ManagementFactory::getThreadMXBean).thenReturn(mock(ThreadMXBean.class));

            Map<String, Object> metrics = (Map<String, Object>) new ApplicationMetricCollector().collectMetric();

            assertEquals(4242L, pid(metrics));
        }
    }

    @Test
    void pidBecomesMinusOneWhenRuntimeNameCannotBeParsed() {
        RuntimeMXBean runtime = mock(RuntimeMXBean.class);
        when(runtime.getName()).thenReturn("not-a-pid");

        try (MockedStatic<ProcessHandle> processHandle = mockStatic(ProcessHandle.class);
             MockedStatic<ManagementFactory> management = mockStatic(ManagementFactory.class)) {
            processHandle.when(ProcessHandle::current)
                    .thenThrow(new IllegalStateException("no pid available"));
            management.when(ManagementFactory::getRuntimeMXBean).thenReturn(runtime);
            management.when(ManagementFactory::getThreadMXBean).thenReturn(mock(ThreadMXBean.class));

            Map<String, Object> metrics = (Map<String, Object>) new ApplicationMetricCollector().collectMetric();

            assertEquals(-1L, pid(metrics));
        }
    }

    @Test
    void hostnameStaysUnknownWhenLocalHostLookupFails() {
        SystemUtils.clearHostnameCache();

        try (MockedStatic<InetAddress> inet = mockStatic(InetAddress.class)) {
            inet.when(InetAddress::getLocalHost).thenThrow(new UnknownHostException("no resolvable host"));

            Map<String, Object> metrics = (Map<String, Object>) new ApplicationMetricCollector().collectMetric();

            assertEquals("unknown", metrics.get("hostname"));
        }
    }
}
