# Repository Guidelines

## Project Structure & Modules
- Multi-module Gradle project (Java 17). Key modules: `core`, `runtime`, `registry`, `mq`, `state`, `window`, `aggregation`, `join`, `cdc`, `sink`, `source`, `metrics`, `spring-boot-starter`, `examples` (see `settings.gradle`).
- Code: `*/src/main/java`; tests: `*/src/test/java`; resources in corresponding `resources` dirs.
- Dev assets: `docker-compose.yml` (Redis for tests), `docs/`, `README.md`, `TESTING.md`.

## Build, Test, and Development
- Build all: `./gradlew build` (includes unit tests).
- Module build: `./gradlew :core:build`.
- Unit tests: `./gradlew test` or `./gradlew :aggregation:test`.
- Integration tests (require Redis): `docker-compose up -d && ./gradlew integrationTest && docker-compose down`.
- Full check (unit + integration): `./gradlew check`.

## Coding Style & Naming
- Java 17, UTF-8; 4-space indent; braces on same line; keep methods focused and well-documented where non-obvious.
- Packages: `io.github.cuihairu.redis.streaming.<module>…`.
- Logging via SLF4J; avoid `System.out`.
- Lombok is available; prefer clear constructors/builders for public APIs.
- Tests: unit `*Test`; integration examples may use `*IntegrationExample`. Always tag integration tests with `@Tag("integration")`.

## Testing Guidelines
- Frameworks: JUnit 5, Mockito.
- Unit tests must not require Redis; integration tests must be annotated `@Tag("integration")` and can assume Redis at `redis://127.0.0.1:6379` (override `REDIS_URL`).
- Recommended coverage (via JaCoCo): core ≥ 80%, others ≥ 70%.
- Useful invocations: `./gradlew :core:test --tests "ClassNameTest"`, `./gradlew integrationTest --info`.

## Commit & Pull Request Guidelines
- Use Conventional Commits (e.g., `feat:`, `fix:`, `refactor:`). Example: `refactor: migrate publishing to Central Portal`.
- Before pushing: `./gradlew clean check` and ensure `docker-compose down` is executed.
- PRs should include: clear description, linked issues, test coverage for changes, and docs updates when behavior or APIs change. Add screenshots/log snippets when relevant.

## Security & Configuration
- Publishing uses Central Portal; set `CENTRAL_PORTAL_USERNAME`/`CENTRAL_PORTAL_TOKEN`. GPG signing keys are read from env (`GPG_KEY_ID`, `GPG_PASSWORD`, `GPG_SECRET_KEY`).
- Local tests only need Docker; no secrets required.
