# Quick Start (EN)

[中文](../Quick-Start.md) | [English](Quick-Start-en.md)

---

This page covers build, test, and example execution. For full details see:
- Root docs: [QUICK_START.md](https://github.com/cuihairu/redis-streaming/blob/main/QUICK_START.md), [RUNNING_EXAMPLES.md](https://github.com/cuihairu/redis-streaming/blob/main/RUNNING_EXAMPLES.md), [TESTING.md](https://github.com/cuihairu/redis-streaming/blob/main/TESTING.md)
- Spring Boot: [Spring-Boot-Starter](../Spring-Boot-Starter.md) and [spring-boot-starter-guide](../spring-boot-starter-guide.md)

## 1) Prerequisites

- Java 17+ (build script pins `options.release = 17`)
- Docker (for integration tests/examples Redis; repo scripts use `docker compose` V2)
- Gradle Wrapper (bundled, Gradle 8.5)

## 2) Build & Unit Tests

```bash
./gradlew build     # builds all modules; build depends on check, runs unit + integration tests
# unit tests only (no integration tests, no Redis needed)
./gradlew test
```

Note: `build`/`check` execute `integrationTest` (requires Redis). Without Redis, run step 3 first or just `./gradlew test`.

## 3) Integration Tests (require Redis)

```bash
# Start minimal Redis (docker-compose.minimal.yml, redis:7-alpine)
docker compose -f docker-compose.minimal.yml up -d

# Run integration tests only
./gradlew integrationTest

# Stop containers
docker compose -f docker-compose.minimal.yml down
```

Tips
- Integration tests are tagged `@Tag("integration")`; `test` task excludes them by default.
- Run a single test class:
  ```bash
  ./gradlew :reliability:integrationTest --tests "RedisSlidingWindowRateLimiterIntegrationExample"
  ```
- Integration tests connect to `REDIS_URL` (default `redis://127.0.0.1:6379`).

## 4) Run Examples

See root RUNNING_EXAMPLES.md; typical steps:
```bash
# 1) start dependencies
docker compose up -d

# 2) run an example (specify entry via -PmainClass, default is registry.ServiceRegistryExample)
./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.mq.MessageQueueExample
```

Available example entry points in [Examples](../Examples.md).

## 5) Spring Boot Integration (Minimal)

Gradle dependency (current 0.2.0):
```gradle
implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.2.0'
```

Enable in app:
```java
@SpringBootApplication
@EnableRedisStreaming
public class Application {
  public static void main(String[] args){ SpringApplication.run(Application.class, args); }
}
```

Minimal `application.yml`:
```yaml
spring:
  application:
    name: demo
redis-streaming:
  redis:
    address: redis://127.0.0.1:6379   # default, can omit
  mq:
    enabled: true                      # true by default (matchIfMissing)
```

Metrics (optional): see [Metrics](../Metrics.md) for Actuator/Prometheus setup.

## 6) Troubleshooting

- Verify Java 17: `java -version`
- Integration tests fail/hang: check Redis is running (`redis-cli PING` should return PONG)
- CI/CD reference: [GitHub Actions](../GitHub-Actions.md)

---

**Version**: 0.2.0
**Last Updated**: 2026-10-05