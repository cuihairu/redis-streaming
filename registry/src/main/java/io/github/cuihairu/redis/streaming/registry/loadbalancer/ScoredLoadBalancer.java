package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Score-based selection combining weight, locality, and metrics (cpu/latency).
 */
public class ScoredLoadBalancer implements LoadBalancer {
    private final LoadBalancerConfig cfg;
    private final MetricsProvider metricsProvider;

    public ScoredLoadBalancer(LoadBalancerConfig cfg, MetricsProvider metricsProvider) {
        this.cfg = cfg != null ? cfg : new LoadBalancerConfig();
        this.metricsProvider = Objects.requireNonNull(metricsProvider, "metricsProvider");
    }

    @Override
    public ServiceInstance choose(String serviceName, List<ServiceInstance> candidates, Map<String, Object> context) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        return candidates.stream()
                .map(ins -> new ScorePair(ins, score(serviceName, ins)))
                .filter(p -> p.score > Double.NEGATIVE_INFINITY / 2)
                .max(Comparator.comparingDouble(p -> p.score))
                .map(p -> p.ins)
                .orElse(candidates.get(0));
    }

    private static class ScorePair {
        final ServiceInstance ins;
        final double score;
        ScorePair(ServiceInstance i, double s) { this.ins = i; this.score = s; }
    }

    private double score(String serviceName, ServiceInstance ins) {
        double score = 1.0;

        // base weight
        int w = ins.getWeight();
        try {
            String mdw = ins.getMetadata().get("weight");
            if (mdw != null) w = Integer.parseInt(mdw);
        } catch (Exception ignore) {}
        if (w < 1) w = 1;
        score *= (1.0 + cfg.getBaseWeightFactor() * Math.log10(w + 1));

        // locality boosts
        if (cfg.getPreferredRegion() != null && cfg.getPreferredRegion().equals(ins.getMetadata().get("region"))) {
            score *= cfg.getRegionBoost();
        }
        if (cfg.getPreferredZone() != null && cfg.getPreferredZone().equals(ins.getMetadata().get("zone"))) {
            score *= cfg.getZoneBoost();
        }

        // metrics
        Map<String, Object> m = metricsProvider.getMetrics(serviceName, ins.getInstanceId());

        // hard constraints: exclude instance if exceeded
        if (exceeds(m, cfg.getCpuKey(), cfg.getMaxCpuPercent()) ||
            exceeds(m, cfg.getLatencyKey(), cfg.getMaxLatencyMs()) ||
            exceeds(m, cfg.getMemoryKey(), cfg.getMaxMemoryPercent()) ||
            exceeds(m, cfg.getInflightKey(), cfg.getMaxInflight()) ||
            exceeds(m, cfg.getQueueKey(), cfg.getMaxQueue()) ||
            exceeds(m, cfg.getErrorRateKey(), cfg.getMaxErrorRatePercent())) {
            return Double.NEGATIVE_INFINITY;
        }

        // lower CPU better: factor in [0,1]
        double cpu = getDouble(m.get(cfg.getCpuKey()), -1);
        if (cpu >= 0) {
            double cpuFactor = Math.max(0.0, 1.0 - Math.min(100.0, cpu) / 100.0);
            score *= Math.pow(cpuFactor, cfg.getCpuWeight());
        }

        // lower latency better: factor in (0,1]
        double lat = getDouble(m.get(cfg.getLatencyKey()), -1);
        if (lat >= 0) {
            double t = Math.max(1.0, cfg.getTargetLatencyMs());
            double latFactor = t / (t + Math.max(0.0, lat));
            score *= Math.pow(latFactor, cfg.getLatencyWeight());
        }

        // memory usage (lower is better)
        if (cfg.getMemoryWeight() > 0) {
            double mem = getDouble(m.get(cfg.getMemoryKey()), -1);
            if (mem >= 0) {
                double memFactor = Math.max(0.0, 1.0 - Math.min(100.0, mem) / 100.0);
                score *= Math.pow(memFactor, cfg.getMemoryWeight());
            }
        }

        // inflight (lower is better)
        if (cfg.getInflightWeight() > 0) {
            double in = getDouble(m.get(cfg.getInflightKey()), -1);
            if (in >= 0) {
                double inFactor = 1.0 / (1.0 + Math.max(0.0, in));
                score *= Math.pow(inFactor, cfg.getInflightWeight());
            }
        }

        // queue depth (lower is better)
        if (cfg.getQueueWeight() > 0) {
            double q = getDouble(m.get(cfg.getQueueKey()), -1);
            if (q >= 0) {
                double qFactor = 1.0 / (1.0 + Math.max(0.0, q));
                score *= Math.pow(qFactor, cfg.getQueueWeight());
            }
        }

        // error rate (lower is better, percent)
        if (cfg.getErrorRateWeight() > 0) {
            double er = getDouble(m.get(cfg.getErrorRateKey()), -1);
            if (er >= 0) {
                double erFactor = Math.max(0.0, 1.0 - Math.min(100.0, er) / 100.0);
                score *= Math.pow(erFactor, cfg.getErrorRateWeight());
            }
        }

        return score;
    }

    private static double getDouble(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    private static boolean exceeds(Map<String, Object> m, String key, double limit) {
        if (limit < 0) return false;
        Object v = m.get(key);
        if (v == null) return false;
        double d = getDouble(v, -1);
        return d >= 0 && d > limit;
    }
}
