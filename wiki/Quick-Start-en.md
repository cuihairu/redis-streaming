# Quick Start (EN)

This page helps you build, test, and try the framework in minutes. For full details, see:
- Root docs: QUICK_START.md, RUNNING_EXAMPLES.md, TESTING.md
- Spring Boot Starter: Spring-Boot-Starter-en.md

## 1) Prerequisites
- Java 17+
- Docker (for running Redis in integration tests and examples)
- Gradle Wrapper (provided)

## 2) Build & Unit Tests
```bash
./gradlew clean build     # builds all modules and runs unit tests
# or only unit tests
./gradlew test
```

## 3) Integration Tests (require Redis)
```bash
# start minimal Redis
docker-compose -f docker-compose.minimal.yml up -d

# run integration tests only
./gradlew integrationTest

# stop containers
docker-compose -f docker-compose.minimal.yml down
```

Tips
- Integration tests are tagged with `@Tag("integration")` and are separated from unit tests.
- You can run a single test class:
  ```bash
  ./gradlew :reliability:integrationTest --tests "RedisSlidingWindowRateLimiterIntegrationExample"
  ```

## 4) Run Examples
See RUNNING_EXAMPLES.md for end-to-end demos. Typical steps:
```bash
# 1) start dependencies
docker-compose up -d

# 2) run one example (replace with the one you need)
./gradlew :examples:run --args='mq-basic'
```

## 5) Spring Boot Integration (Minimal)
Gradle dependency:
```gradle
implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.1.0'
```

Enable in your app:
```java
@SpringBootApplication
@EnableRedisStreaming
public class Application {
  public static void main(String[] args){ SpringApplication.run(Application.class, args); }
}
```

application.yml (minimal):
```yaml
spring:
  application:
    name: demo
redis-streaming:
  mq:
    enabled: true
```

Expose metrics (optional): see Spring-Boot-Starter-en.md for Actuator/Prometheus setup.

## 6) Troubleshooting
- Ensure Java 17 is used: `java -version`
- If integration tests hang, check Redis is running (ping `redis-cli PING`)
- For CI/CD setup, see docs/github-actions.md
