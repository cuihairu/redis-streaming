# Testing (EN)

This page summarizes how to run unit/integration tests locally and in CI. See TESTING.md for full details.

## 1) Unit Tests
```bash
./gradlew test                  # run unit tests in all modules
./gradlew :core:test            # run a single module
./gradlew :core:test --tests "ClassNameTest"   # a single test class
```

Notes
- Unit tests must not require Redis.
- Recommended coverage (via JaCoCo): core ≥ 80%, others ≥ 70%.

## 2) Integration Tests (require Redis)
```bash
# start Redis (minimal)
docker-compose -f docker-compose.minimal.yml up -d

# run integration tests only
./gradlew integrationTest

# stop containers
docker-compose -f docker-compose.minimal.yml down
```

Notes
- Integration tests are tagged with `@Tag("integration")` and are excluded from `test`.
- Run one class:
  ```bash
  ./gradlew :reliability:integrationTest --tests "RedisTokenBucketRateLimiterIntegrationExample"
  ```

## 3) CI Tips
- Ensure Java 17 in runners (`java -version`).
- Prefer using Docker Compose files in repository for dependent services.
- For flaky Redis timing, allow short waits or retries in ITs; see docs/github-actions.md.
