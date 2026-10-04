# Testing Guide

This guide explains how to run tests in the streaming framework project.

## Overview

The project separates tests into two categories:

1. Unit tests - fast tests that don't require external dependencies (Redis)
2. Integration tests - tests tagged `@Tag("integration")` that require Redis (and, for CDC, MySQL/PostgreSQL)

## Quick Start

### Run Unit Tests Only (No Redis Required)

```bash
# Run all unit tests
./gradlew test

# Run tests for a specific module
./gradlew :core:test
./gradlew :aggregation:test
./gradlew :cdc:test
```

### Run Integration Tests (Redis Required)

```bash
# 1. Start Redis (docker compose V2 CLI, same as .github/workflows/ci.yml and ./test-env.sh)
docker compose up -d

# 2. Run integration tests
./gradlew integrationTest

# 3. Stop Redis
docker compose down
```

## Test Configuration

### Gradle Test Tasks

The project provides these test tasks (see root `build.gradle`):

- `test` runs unit tests only (excludes `@Tag("integration")`)
- `integrationTest` runs integration tests only (includes `@Tag("integration")`); registered per module and serialized across modules via a shared Gradle build service (`maxParallelUsages = 1`) because integration tests share one Redis
- `check` runs unit tests, integration tests, and the coverage gate (`jacocoRootCoverageVerification`: unit-test execution data only, INSTRUCTION ≥ 0.95 and CLASS ≥ 0.99; connector classes that cannot run without Kafka/MySQL binlog/PostgreSQL replication are excluded from the gate)
- `jacocoRootReport` produces the aggregated JaCoCo report (XML at `build/reports/jacoco/jacocoRootReport/jacocoRootReport.xml`)

All `Test` tasks run with `maxHeapSize = 1536m` (the `:mq:test` suite OOMs with the 512m default).

### Test Tags

Tests are organized using JUnit 5 tags:

- Unit tests: no tag (default)
- Integration tests: `@Tag("integration")`

Example integration test:
```java
@Tag("integration")
public class RedisRegistryIntegrationExample {
    @Test
    public void testServiceRegistryAndDiscovery() throws Exception {
        // Test code that requires Redis
    }
}
```

## Environment Setup

### Docker Compose

The repository ships three compose files:

- `docker-compose.yml` - development environment (Redis, MySQL, PostgreSQL, Elasticsearch, with persistence)
- `docker-compose.test.yml` - CI/test environment (same services, tmpfs/no persistence, health checks)
- `docker-compose.minimal.yml` - Redis only (enough for most integration tests)

```bash
# Start services
docker compose up -d

# Check status
docker compose ps

# View logs
docker compose logs -f redis

# Stop services
docker compose down

# Stop and remove volumes
docker compose down -v
```

The `./test-env.sh` helper wraps `docker-compose.test.yml` with the `docker compose` command:

```bash
./test-env.sh start    # start all test services
./test-env.sh status   # show status
./test-env.sh logs     # logs (all services or one: ./test-env.sh logs redis)
./test-env.sh test     # auto-start environment if needed, then run tests
./test-env.sh restart
./test-env.sh stop     # stop and cleanup
```

### Redis Configuration

Integration tests use the `REDIS_URL` environment variable:

```bash
# Default
redis://127.0.0.1:6379

# Custom Redis
export REDIS_URL=redis://custom-host:6379
./gradlew integrationTest
```

### CDC Manager Integration Tests

`CDCManagerLifecycleMultiConnectorIntegrationTest` (module `:cdc`) covers manager-level
start/stop/restart and multi-connector concurrency with real `DatabasePollingCDCConnector`s:

- The lifecycle/concurrency legs run against an embedded H2 database. No external
  services are needed, so they always execute as part of `integrationTest`/`check`.
- The Redis MQ bridge leg (`managerEventsBridgeToRealRedisMqTopic`) forwards captured
  change events through `ChangeEventQueueSink` onto a real Redis-backed MQ topic and asserts
  delivery via a real consumer. It requires a reachable Redis (`REDIS_URL`, default
  `redis://127.0.0.1:6379`); when none is reachable the test is skipped automatically with
  a message. To trigger it:

  ```bash
  # Option 1: full test environment (Redis + MySQL + PostgreSQL + Elasticsearch)
  docker compose -f docker-compose.test.yml up -d

  # Option 2: any running Redis
  export REDIS_URL=redis://localhost:6379

  ./gradlew :cdc:integrationTest --tests "CDCManagerLifecycleMultiConnectorIntegrationTest"
  ```

- The MySQL/PostgreSQL connector-level integration tests
  (`DatabasePollingCDCConnectorIntegrationTest`, `CDCDisconnectReconnectIntegrationTest`,
  `CDCPositionResumeIntegrationTest`) additionally require `MYSQL_URL` / `POSTGRES_URL`
  and skip via JUnit assumptions when those variables are not set.

## Common Test Scenarios

### Daily Development

```bash
# Quick feedback - run unit tests only
./gradlew test --parallel
```

### Before Commit

```bash
# Run all tests (unit + integration + coverage gate) to ensure nothing is broken
docker compose up -d
./gradlew clean check
docker compose down
```

### Specific Module Testing

```bash
# Test specific module (unit tests)
./gradlew :core:test

# Test specific module (integration tests)
docker compose up -d
./gradlew :registry:integrationTest
docker compose down
```

### Specific Test Class

```bash
# Run specific unit test class (MessageTest lives in the :mq module)
./gradlew :mq:test --tests "MessageTest"

# Run specific integration test (RedisRegistryIntegrationExample lives in :registry)
docker compose up -d
./gradlew :registry:integrationTest --tests "RedisRegistryIntegrationExample"
docker compose down
```

### Test with Debug Output

```bash
# Run with info level logging
./gradlew test --info

# Run with debug logging
./gradlew test --debug

# Show standard output
./gradlew test --console=plain
```

## CI/CD Integration

CI runs on GitHub Actions (`.github/workflows/ci.yml`, workflow name `CI`):

1. `actions/checkout@v6` with `fetch-depth: 0` (axion-release needs tags)
2. `actions/setup-java@v5`, Temurin 17, Gradle cache
3. `docker compose -f docker-compose.test.yml up -d`, then wait until all 4 services are healthy (timeout 180s)
4. `./gradlew clean check jacocoRootReport --warning-mode=all` with `REDIS_URL`, `MYSQL_URL`, `POSTGRES_URL`, `ELASTICSEARCH_URL` pointed at localhost
5. Upload `build/reports/jacoco/jacocoRootReport/jacocoRootReport.xml` to Codecov (`codecov-action@v5`, `fail_ci_if_error: false`)
6. Tear down with `docker compose -f docker-compose.test.yml down -v`

Publishing to Maven Central happens in the same workflow when a release is published, a `v*` tag is pushed, or the workflow is dispatched with a version. See `docs/GitHub-Actions.md` for details.

## Test Module Structure

```
streaming/
├── registry/
│   └── src/test/java/
│       ├── *Test.java                    # Unit tests
│       └── RedisRegistryIntegrationExample.java   # Integration tests (@Tag("integration"))
├── aggregation/
│   └── src/test/java/
│       ├── *Test.java                    # Unit tests
│       └── AggregationIntegrationExample.java     # Integration tests (@Tag("integration"))
└── docker-compose.yml                    # Test infrastructure
```

Unit and integration tests share `src/test/java`; the `integrationTest` source set reuses it and selects by tag.

## Test Coverage

Run the aggregated coverage report (JaCoCo, configured in the root `build.gradle`):

```bash
./gradlew test jacocoRootReport

# Aggregate HTML report
open build/reports/jacoco/jacocoRootReport/html/index.html

# Per-module report (example)
open core/build/reports/jacoco/test/html/index.html
```

`./gradlew check` additionally enforces the coverage verification
(`jacocoRootCoverageVerification`): computed from unit-test execution data only
(`jacoco/test.exec`) — INSTRUCTION ≥ 0.95 and CLASS ≥ 0.99 across all published
modules, excluding `**/kafka/**`, `MySQLBinlogCDCConnector*`, and
`PostgreSQLLogicalReplicationCDCConnector*` (these require external brokers/databases to execute).
The unit-only basis is deterministic; the unit+integration union is reported separately via
`jacocoRootReport` and uploaded to Codecov.

## Troubleshooting

### Tests Fail with "Connection refused"

Integration tests can't connect to Redis. Check:
```bash
# Make sure Redis is running
docker compose ps

# Check Redis health (container name from the compose files)
docker exec streaming-redis-test redis-cli ping
# Should return: PONG

# Restart Redis if needed
docker compose restart redis
```

### Unit Tests Run Integration Tests

Integration tests run during `./gradlew test`. Make sure integration test classes have the `@Tag("integration")` annotation:
```java
@Tag("integration")
public class MyIntegrationTest {
    // ...
}
```

`./gradlew build` runs `check`, which includes `integrationTest` — if you only want unit
tests without Redis, run `./gradlew test`.

### Gradle Wrapper Issues

`./gradlew` fails. Regenerate the wrapper (the checked-in wrapper is Gradle 8.5):

```bash
gradle wrapper --gradle-version 8.5

# Make executable
chmod +x gradlew
```

## Best Practices

1. Keep unit tests fast - mock external dependencies
2. Make integration tests reliable - use docker compose for a consistent environment
3. Clean up after integration tests - stop containers when done
4. Run unit tests frequently - they're fast and need no setup
5. Run integration tests before commits - catch integration issues early
6. Use tags consistently - all integration tests should have `@Tag("integration")`
7. Do not depend on Redis in unit tests - the `test` task must stay Redis-free

## Module-Specific Notes

### Registry / MQ Modules
- The registry (`:registry`) and MQ (`:mq`) integration tests are the most Redis-heavy; start the minimal Redis before running them
- `RedisRegistryIntegrationExample` (`:registry`) exercises registration + discovery against a real Redis

### Aggregation Module
- Tests PV counter and Top-K analyzer
- Integration tests (e.g. `AggregationIntegrationExample`, `AnalyticsIntegrationTest`) verify Redis sorted set operations

### CDC Module
- Lifecycle/concurrency integration legs run on an embedded H2 database (no external services)
- MySQL/PostgreSQL connector tests skip via JUnit assumptions when `MYSQL_URL` / `POSTGRES_URL` are unset
- The Redis MQ bridge leg skips automatically when no Redis is reachable

### Sink/Source Modules
- File-based tests (`FileSinkTest`, `FileSourceTest`) don't require Redis
- Kafka sink/source integration tests require a Kafka broker; Redis sink/source integration tests use `REDIS_URL`

## Further Reading

- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
- [Gradle Testing](https://docs.gradle.org/current/userguide/java_testing.html)
- [Docker Compose](https://docs.docker.com/compose/)
