package io.github.cuihairu.redis.streaming.benchmark;

/**
 * One benchmark's measurement: total operations, wall time, throughput and latency percentiles.
 *
 * @param name        benchmark name
 * @param operations  number of measured operations (messages sent, checkpoints triggered, ...)
 * @param elapsedMs   wall time of the measured phase in milliseconds
 * @param opsPerSec   operations per second
 * @param p50Ms       median per-operation latency in milliseconds (when measured)
 * @param p95Ms       95th percentile per-operation latency in milliseconds (when measured)
 * @param p99Ms       99th percentile per-operation latency in milliseconds (when measured)
 */
public record BenchmarkResult(String name, long operations, long elapsedMs, double opsPerSec,
                              double p50Ms, double p95Ms, double p99Ms) {

    /** Column-formatted single line for the runner's result table. */
    public String toLine() {
        return String.format("%-34s ops=%-8d %8.0f ops/s  p50=%7.2fms  p95=%7.2fms  p99=%7.2fms",
                name, operations, opsPerSec, p50Ms, p95Ms, p99Ms);
    }
}
