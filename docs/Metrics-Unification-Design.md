# Metrics Unification Design (four stacks → one Micrometer facade)

This page is for review and contains no code changes. Goal: the repo currently has four disconnected "metrics" stacks, two of them dead code; converge on Micrometer as the only facade.

## Current State (fact inventory)

| Location | What it is | Production-side usage |
| --- | --- | --- |
| the `metrics` module (`MetricCollector/MetricRegistry/InMemoryMetricCollector/MetricTimer/prometheus` etc.) | a homegrown metrics facade + in-memory implementation + Prometheus output | **zero production consumers** (outside examples); de facto dead code |
| `reliability` `metrics/ReliabilityMetrics` | a metrics bridge | **zero callers**, a dead bridge |
| `cdc` `CDCMetrics` | an immutable POJO (counter snapshots) | used by the CDC connectors, but **without a Micrometer bridge**, so users get no runtime observability |
| `runtime` `RedisRuntimeMetrics` (+`RedisRuntimeMetricsCollector`) | runtime metrics (watermark, latency, in-flight, etc.) | **the only one actually running**, called from several places inside the runtime |

Other facts:

1. Only the `spring-boot-starter` depends on Micrometer (`api libs.micrometer.core`), and there is precedent: the RateLimit metrics already bridge into Micrometer via `@ConditionalOnClass(MeterRegistry)` (`RedisStreamingAutoConfiguration`).
2. The `metrics` module has no Micrometer dependency and runs parallel to the starter bridge.
3. At least three naming formats coexist (`topic.group`, `job.topic.group`, mixed camel/snake); cross-module aggregation/comparison is impossible.
4. The `runtime` metrics carry job/topic/consumer-group dimensions (e.g. `setWatermarkMs(job, topic, group, ms)`) — the only stack of the four with complete dimensions.

## Problems

- A user wiring Micrometer/Prometheus sees only the RateLimit and part of the runtime metrics; CDC and reliability are entirely unobservable.
- The `metrics` module keeps costing maintenance (tests, the coverage gate, API evolution) with nobody using it.
- A homegrown facade cannot reuse the Micrometer ecosystem (registry auto-configuration, Boot metric export, histogram publishing, etc.).

## Candidate Approaches

### Approach A: Micrometer as the only facade; the runtime metrics as the baseline (recommended)

- **The standard**: one and only one outward observability path — Micrometer `MeterRegistry`. Naming unifies on `redis.streaming.<module>.<metric>` (lowercase, dot-separated), with dimensions uniformly `job`/`topic`/`group`/`tenant` (reserved).
- **The runtime metrics become a public SPI**: the interface part of `RedisRuntimeMetrics` moves to a common location (or an extracted `StreamingMetrics` interface) with the implementation staying in the runtime; the starter auto-bridges with the same `@ConditionalOnClass(MeterRegistry)` pattern as RateLimit.
- **CDC**: `CDCMetrics` stays a pure snapshot POJO (useful for in-process polling), plus a Micrometer exporter (bound to the registry at starter auto-configuration), with connectors recording after each collection round.
- **Cleanup**: delete `reliability`'s dead `ReliabilityMetrics` bridge; the `metrics` module is deleted in v2 (a major), its Prometheus output taken over by Micrometer's `micrometer-registry-prometheus`.

### Approach B: keep the homegrown `metrics` facade; bridge each module into it

Point the starter's Micrometer bridge at "bridging into the metrics module", and have the metrics module emit Prometheus. One more homegrown abstraction to maintain forever, and against the Spring Boot ecosystem's established habits — **not recommended**.

### Approach C: each module depends on Micrometer directly

No common interface; each module calls `Metrics.counter(...)` itself. Fastest short term, but naming/dimension conventions inevitably drift (already the case today) and need documentation discipline as a backstop — acceptable as a transitional shape while Approach A lands; not the end state.

## Migration Path (Approach A, phased)

1. **v1** (landed 2026-10-11): the runtime metrics interface goes public + the starter auto-bridges Micrometer; the CDC exporter; the unified naming convention written into the docs; the `ReliabilityMetrics` dead bridge deleted. The actual landing follows todo.md's records (DLQ replay observability, dead-bridge deletion, the CDC Micrometer binding, the naming convention document).
2. **v2 (major, landed 2026-10-12)**: the `metrics` module is deleted (including `MetricCollector/InMemoryMetricCollector/MetricRegistry/MetricTimer/Metric` and `prometheus.*`; verified zero out-of-repo consumers); the two simpleclient coordinates in `gradle/libs.versions.toml` removed with it; [Metrics.md](Metrics.md) carries the "From InMemoryMetricCollector to Micrometer" migration table, with Prometheus output delegated to `micrometer-registry-prometheus`. The runtime metrics interface going public landed in the same batch: `RedisRuntimeMetricsCollector` renamed and promoted to core's public SPI `io.github.cuihairu.redis.streaming.api.metrics.StreamingMetricsCollector` (the only outward-facing interface of the runtime metrics vocabulary; the `RedisRuntimeMetrics` static facade and the starter's Micrometer bridge stay where they are, retyped only).

## Non-Goals

- No homegrown metric storage/time-series DB; transport (Pushgateway, OTLP, etc.) is Micrometer's job.
- No metric gating/sampling (if needed, Micrometer has `MeterFilter`).
- No expansion of the metrics coverage inside this design (new metrics are separate proposals).
