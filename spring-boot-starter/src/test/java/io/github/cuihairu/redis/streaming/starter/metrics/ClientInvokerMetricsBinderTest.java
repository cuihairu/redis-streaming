package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.registry.client.ClientInvoker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class ClientInvokerMetricsBinderTest {

    @Test
    public void testGaugesReadFromSnapshot() {
        ClientInvoker invoker = mock(ClientInvoker.class);
        when(invoker.getMetricsSnapshot()).thenReturn(Map.of(
                "total", Map.of(
                        "attempts", 10L,
                        "successes", 7L,
                        "failures", 3L,
                        "retries", 2L,
                        "cbOpenSkips", 1L
                )
        ));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ClientInvokerMetricsBinder(invoker).bindTo(registry);

        assertEquals(10.0, registry.get("client.invoker.total.attempts").gauge().value(), 0.0001);
        assertEquals(7.0, registry.get("client.invoker.total.successes").gauge().value(), 0.0001);
        assertEquals(3.0, registry.get("client.invoker.total.failures").gauge().value(), 0.0001);
        assertEquals(2.0, registry.get("client.invoker.total.retries").gauge().value(), 0.0001);
        assertEquals(1.0, registry.get("client.invoker.total.cbOpenSkips").gauge().value(), 0.0001);
    }

    @Test
    public void testErrorsAreSwallowed() {
        ClientInvoker invoker = mock(ClientInvoker.class);
        when(invoker.getMetricsSnapshot()).thenThrow(new RuntimeException("boom"));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ClientInvokerMetricsBinder(invoker).bindTo(registry);

        assertEquals(0.0, registry.get("client.invoker.total.attempts").gauge().value(), 0.0001);
    }
}

