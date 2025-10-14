package io.github.cuihairu.redis.streaming.registry.client;

import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.client.metrics.RedisClientMetricsReporter;
import io.github.cuihairu.redis.streaming.registry.client.ClientSelectorConfig;
import io.github.cuihairu.redis.streaming.registry.client.ClientSelector;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.LoadBalancer;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Client-side invoker: choose instance (filters+LB), apply circuit breaker and retry, update client metrics.
 */
public class ClientInvoker {
    private final NamingService namingService;
    private final LoadBalancer loadBalancer;
    private final ClientSelector selector;
    private final RetryPolicy retryPolicy;
    private final RedisClientMetricsReporter reporter;
    private final ClientInvokerMetrics invokerMetrics = new ClientInvokerMetrics();

    // per-instance circuit breaker registry (uniqueId -> breaker)
    private final java.util.concurrent.ConcurrentHashMap<String, CircuitBreaker> breakers = new java.util.concurrent.ConcurrentHashMap<>();

    public ClientInvoker(NamingService namingService,
                         LoadBalancer loadBalancer,
                         RetryPolicy retryPolicy,
                         RedisClientMetricsReporter reporter) {
        this.namingService = Objects.requireNonNull(namingService);
        this.loadBalancer = Objects.requireNonNull(loadBalancer);
        this.selector = new ClientSelector(namingService, new ClientSelectorConfig());
        this.retryPolicy = retryPolicy != null ? retryPolicy : new RetryPolicy(3, 10, 2.0, 200, 10);
        this.reporter = reporter;
    }

    public <T> T invoke(String serviceName,
                        Map<String, String> metadataFilters,
                        Map<String, String> metricsFilters,
                        Map<String, Object> lbContext,
                        java.util.function.Function<ServiceInstance, T> fn) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= retryPolicy.getMaxAttempts(); attempt++) {
            invokerMetrics.total().attempts.incrementAndGet();
            invokerMetrics.forService(serviceName).attempts.incrementAndGet();
            ServiceInstance ins = selector.select(serviceName, metadataFilters, metricsFilters, loadBalancer, lbContext);
            if (ins == null) {
                last = new RuntimeException("No available instances for service=" + serviceName);
                invokerMetrics.total().failures.incrementAndGet();
                invokerMetrics.forService(serviceName).failures.incrementAndGet();
                backoff(attempt);
                continue;
            }
            String uid = ins.getUniqueId();
            CircuitBreaker cb = breakers.computeIfAbsent(uid, k -> new CircuitBreaker(20, 0.5, Duration.ofSeconds(5), 1));

            if (!cb.allow()) {
                last = new RuntimeException("Circuit breaker open for " + uid);
                invokerMetrics.total().cbOpenSkips.incrementAndGet();
                invokerMetrics.forService(serviceName).cbOpenSkips.incrementAndGet();
                backoff(attempt);
                continue;
            }

            long start = System.nanoTime();
            if (reporter != null) reporter.incrementInflight(serviceName, ins.getInstanceId());
            try {
                T res = fn.apply(ins);
                cb.onSuccess();
                long lat = (System.nanoTime() - start) / 1_000_000;
                if (reporter != null) {
                    reporter.recordLatency(serviceName, ins.getInstanceId(), lat);
                    reporter.recordOutcome(serviceName, ins.getInstanceId(), true);
                }
                invokerMetrics.total().successes.incrementAndGet();
                invokerMetrics.forService(serviceName).successes.incrementAndGet();
                return res;
            } catch (Exception e) {
                cb.onFailure();
                long lat = (System.nanoTime() - start) / 1_000_000;
                if (reporter != null) {
                    reporter.recordLatency(serviceName, ins.getInstanceId(), lat);
                    reporter.recordOutcome(serviceName, ins.getInstanceId(), false);
                }
                last = e;
                if (attempt < retryPolicy.getMaxAttempts()) {
                    invokerMetrics.total().retries.incrementAndGet();
                    invokerMetrics.forService(serviceName).retries.incrementAndGet();
                }
                backoff(attempt);
            } finally {
                if (reporter != null) reporter.decrementInflight(serviceName, ins.getInstanceId());
            }
        }
        throw last != null ? last : new RuntimeException("Invocation failed");
    }

    private void backoff(int attempt) {
        long d = retryPolicy.computeDelayMs(attempt);
        try { Thread.sleep(d); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    /**
     * Returns a snapshot of invoker counters (total + per service).
     */
    public Map<String, Map<String, Long>> getMetricsSnapshot() {
        return invokerMetrics.snapshot();
    }
}
