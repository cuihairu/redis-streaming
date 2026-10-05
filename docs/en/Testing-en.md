# Testing (EN)

[中文](../Testing.md) | [English](Testing-en.md)

---

## 1) Unit Tests

```bash
./gradlew test
```
- No external dependencies (Mockito for mocking).
- Default `test` task excludes `@Tag("integration")` tests.

## 2) Integration Tests (require Redis)

```bash
# Start Redis (minimal)
docker compose -f docker-compose.minimal.yml up -d

# Run integration tests only
./gradlew integrationTest

# Stop containers
docker compose -f docker-compose.minimal.yml down
```

All integration tests tagged `@Tag("integration")`, connect to `REDIS_URL` (default `redis://127.0.0.1:6379`).

## 3) CI Tips

- `./gradlew clean check` runs unit + integration + coverage gate.
- Coverage gate: `jacocoRootCoverageVerification` (unit-test deterministic gate: INSTRUCTION ≥ 0.95 AND CLASS ≥ 0.99).
- Coverage report (unit+integration union): `./gradlew jacocoRootReport` (uploads to Codecov).
- CI uses `docker-compose.test.yml` (Redis, MySQL, PostgreSQL, Elasticsearch) for full matrix.

---

**Version**: 0.2.0
**Last Updated**: 2026-10-05