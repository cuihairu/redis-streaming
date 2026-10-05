# CI/CD

[中文](../GitHub-Actions.md) | [English](GitHub-Actions-en.md)

---

This project's CI/CD uses GitHub Actions; workflow files live in `.github/workflows/` (see the `README.md` in that directory).

## Workflows (the 2 that exist)

### 1. `ci.yml` (name: CI)
- Triggers: push to `main`, `v*` tags, PRs to `main`, Release published, `workflow_dispatch` (with a `version` input to override the release version)
- Runs on `ubuntu-latest` with `timeout-minutes: 60` and this concurrency control (from the workflow):

  ```yaml
  concurrency:
    group: ci-<ref>
    cancel-in-progress: true
  ```

  `<ref>` is `github.ref` (the push/PR branch ref); older runs on the same ref get cancelled
- Steps:
  1. `actions/checkout@v6` (`fetch-depth: 0`, so axion-release can read tags)
  2. `actions/setup-java@v5` (Temurin 17, `cache: gradle`)
  3. `docker compose -f docker-compose.test.yml up -d`, waiting until all 4 services (Redis/MySQL/PostgreSQL/Elasticsearch) are healthy (`timeout 180`)
  4. `./gradlew clean check jacocoRootReport --warning-mode=all` (unit + integration tests + the aggregate coverage gate)
  5. `codecov/codecov-action@v5` uploads `build/reports/jacoco/jacocoRootReport/jacocoRootReport.xml` (`fail_ci_if_error: false`)
  6. Teardown `docker compose -f docker-compose.test.yml down -v`
- Publishing to Maven Central runs after step 4, triggered by any of: Release published / `workflow_dispatch` with a version / a `v*` tag push
  - Authentication: `ORG_GRADLE_PROJECT_mavenCentralUsername/Password`, `centralPortalUsername/Password` (from secrets `CENTRAL_PORTAL_USERNAME` / `CENTRAL_PORTAL_TOKEN`), and an in-memory GPG key (`GPG_PRIVATE_KEY` / `GPG_PASSWORD`)
  - Command: `./gradlew publishAllPublicationsToMavenCentralRepository` (Vanniktech plugin, Central Portal); manual dispatch overrides with `-Pversion=`, otherwise axion-release derives the version from the `v*` tag

### 2. `docs.yml` (name: Docs (GitHub Pages))
- Triggers: push to `main` touching `docs/**` or `.github/workflows/docs.yml`, plus `workflow_dispatch`
- Permissions: `contents: read`, `pages: write`, `id-token: write`; concurrency group `pages`
- Sequence: `actions/checkout@v7` → `actions/setup-node@v7` (Node 24, caching `docs/package-lock.json`) → `npm ci` → `npm run docs:build` (`DOCS_BASE=/<repo>/`) → `actions/upload-pages-artifact@v5` (`docs/.vitepress/dist`) → `actions/deploy-pages@v5`
- The site is published at https://cuihairu.github.io/redis-streaming/

## Local equivalents
```bash
# CI main flow (needs Docker)
docker compose -f docker-compose.test.yml up -d
./gradlew clean check jacocoRootReport
docker compose -f docker-compose.test.yml down -v

# Documentation site
cd docs && npm ci && npm run docs:build
```

## Related configuration
- Coverage scope and gate: `jacocoRootReport` / `jacocoRootCoverageVerification` in the root `build.gradle`
- Codecov: `codecov.yml` and `codecov-action@v5` in the workflow
- Build notes: root `CLAUDE.md`; testing details: `TESTING.md`
