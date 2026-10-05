# Troubleshooting

[中文](../troubleshooting.md) | [English](Troubleshooting-en.md)

---

Common issues across build, test, and runtime. Defaults are taken from the source; for the test flow see [Testing-en](Testing-en.md) and the root `TESTING.md`.

## Build

### Compilation fails with "deprecation" errors
`build.gradle` compiles main code with `-Xlint:deprecation -Xlint:unchecked -Werror` (deprecation fails the build); test tasks are exempt from `-Werror` and `-Xlint:unchecked`. Such a failure means main code touches a `@Deprecated` API — upgrade the call site instead of switching the gate off.

### Test OOM
`build.gradle` sets `maxHeapSize` to `1536m` for every `Test` task (the 512m default OOMs `:mq:test` once the suite grows). If memory is still short, check available machine memory or run a single module.

## Testing

### Integration tests report Connection refused
Integration tests need a reachable Redis:

```bash
docker compose -f docker-compose.minimal.yml up -d
docker exec streaming-redis-test redis-cli ping   # should return PONG
./gradlew integrationTest
```

- The address comes from the `REDIS_URL` environment variable, default `redis://127.0.0.1:6379`.
- For the full environment (Redis + MySQL + PostgreSQL + Elasticsearch) use `docker compose -f docker-compose.test.yml up -d` (same as CI; containers named `streaming-redis-test` etc., wait for the health checks).

### Only unit tests wanted, but integration tests fail
`build` depends on `check`, and `check` includes `integrationTest`. Without Redis, run unit tests only:

```bash
./gradlew test
```

### Flaky interference between integration tests
`build.gradle` registers a shared BuildService (`sharedRedisIntegrationTestService`, `maxParallelUsages = 1`), so each module's `integrationTest` runs serially even in a parallel build, sharing one Redis instance. If you start a second environment by hand, state can leak between them — `docker compose down -v` before starting again.

### CDC integration tests "don't run"
- Lifecycle/concurrency cases run on an embedded H2 — no external services needed.
- MySQL/PostgreSQL connector cases (`DatabasePollingCDCConnectorIntegrationTest` etc.) require `MYSQL_URL` / `POSTGRES_URL`; without them they are skipped via JUnit assumptions (not failures). See [Testing-en](Testing-en.md) for how to enable them.

## Runtime

### Messages are not redelivered (pending backlog)
Pending scanning takes over entries after an idle period: `MqOptions.claimIdleMs` defaults to **300000ms (5 minutes)** and `pendingScanIntervalSec` to 30s. With a slow handler, seeing the same message pending for up to 5 minutes is expected; lowering `claimIdleMs` raises the chance of duplicate delivery (the builder clamps it to ≥1ms).

### Retry storms / odd backoff
The exponential backoff base `retryBaseBackoffMs` defaults to 1000ms with a cap at `retryMaxBackoffMs` 60000ms; the saturating calculation cannot overflow negative. If retries look too frequent, check whether `retryMaxAttempts` (default 5) was raised while the handler keeps failing — failed entries end up in the DLQ.

### DLQ growth
Watch the `redis_streaming_mq_dlq_total` and `redis_streaming_dlq_*` meters (replay/delete/clear). While the handler keeps failing, DLQ entries stay in the stream (XACK only clears the PEL); handle them manually, then replay.

### Steady memory growth (stream retention)
`retentionMaxLenPerPartition` defaults to 100000, `trimIntervalSec` to 60s, and `retentionMs` to 0 (time-based trimming off). Tighten these three when backlog grows beyond expectations, or set DLQ retention per topic (`dlqRetentionMaxLen` / `dlqRetentionMs`).

### Logs lack context for debugging
Enable MDC correlation: `RedisRuntimeConfig.mdcEnabled(true)` (default `false`) plus `mdcSampleRate` (default 1.0, range 0~1). MDC keys: `rs.job` / `rs.topic` / `rs.group` / `rs.consumer` / `rs.id` / `rs.key` / `rs.partition`.

## Documentation site

### 404s / broken links
GitHub Pages paths are **case-sensitive**: link targets must match the actual file names (for example `Architecture.md`, not `ARCHITECTURE.md`; `GitHub-Actions.md`, not `github-actions.md`).

### Local preview
```bash
cd docs
npm ci
npm run docs:dev
# http://localhost:5173 (VitePress default port)
```

## References
- Testing troubleshooting details: root `TESTING.md` and [Testing-en](Testing-en.md)
- CI behavior (health-check waits, publish conditions): [GitHub Actions](GitHub-Actions-en.md)
