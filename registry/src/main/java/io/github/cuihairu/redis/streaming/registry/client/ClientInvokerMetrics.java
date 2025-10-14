package io.github.cuihairu.redis.streaming.registry.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple metrics for ClientInvoker per service and overall.
 */
public class ClientInvokerMetrics {
    public static class Counters {
        public final AtomicLong attempts = new AtomicLong();
        public final AtomicLong successes = new AtomicLong();
        public final AtomicLong failures = new AtomicLong();
        public final AtomicLong retries = new AtomicLong();
        public final AtomicLong cbOpenSkips = new AtomicLong();
    }

    private final Counters total = new Counters();
    private final ConcurrentHashMap<String, Counters> perService = new ConcurrentHashMap<>();

    public Counters total() { return total; }
    public Counters forService(String service) { return perService.computeIfAbsent(service, k -> new Counters()); }

    public Map<String, Map<String, Long>> snapshot() {
        Map<String, Map<String, Long>> m = new ConcurrentHashMap<>();
        m.put("total", toMap(total));
        perService.forEach((k,v) -> m.put(k, toMap(v)));
        return m;
    }

    private static Map<String, Long> toMap(Counters c) {
        return Map.of(
                "attempts", c.attempts.get(),
                "successes", c.successes.get(),
                "failures", c.failures.get(),
                "retries", c.retries.get(),
                "cbOpenSkips", c.cbOpenSkips.get()
        );
    }
}

