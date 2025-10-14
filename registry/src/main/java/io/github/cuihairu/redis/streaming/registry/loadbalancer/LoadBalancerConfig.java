package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import lombok.Getter;
import lombok.Setter;

/**
 * Config for scored load balancer.
 */
@Getter
@Setter
public class LoadBalancerConfig {
    // score weights
    private double baseWeightFactor = 1.0; // multiply by instance weight
    private double cpuWeight = 1.0;        // higher is more impact from CPU usage
    private double latencyWeight = 1.0;    // higher is more impact from latency

    // target/limits
    private double targetLatencyMs = 50.0; // for latency normalization

    // locality preferences
    private String preferredRegion = null; // exact match boosts score
    private String preferredZone = null;   // exact match boosts score
    private double regionBoost = 1.1;      // multiplier if region matches
    private double zoneBoost = 1.05;       // multiplier if zone matches

    // metrics keys
    private String cpuKey = "cpu";          // metrics.cpu
    private String latencyKey = "latency";  // metrics.latency

    // optional extra metrics (lower is better)
    private double memoryWeight = 0.0;     // memory usage percent (0..100)
    private String memoryKey = "memory";   // metrics.memory
    private double inflightWeight = 0.0;   // concurrent inflight requests
    private String inflightKey = "inflight";
    private double queueWeight = 0.0;      // queue depth
    private String queueKey = "queue";
    private double errorRateWeight = 0.0;  // recent error rate percent (0..100)
    private String errorRateKey = "errorRate";

    // hard constraints (negative means disabled)
    private double maxCpuPercent = -1;     // e.g., 80 => exclude above 80
    private double maxLatencyMs = -1;      // e.g., 200 => exclude above 200
    private double maxMemoryPercent = -1;  // exclude above
    private double maxInflight = -1;       // exclude above
    private double maxQueue = -1;          // exclude above
    private double maxErrorRatePercent = -1; // exclude above
}
