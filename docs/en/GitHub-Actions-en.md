# GitHub Actions (EN)

[中文](../GitHub-Actions.md) | [English](GitHub-Actions-en.md)

---

This project uses GitHub Actions for CI/CD. Workflow files are in `.github/workflows/` (cross-reference that directory's `README.md`).

## Workflow Inventory (2 Actual Workflows)

### 1. `ci.yml` (name: **CI**)

- **Triggers**: push to `main`, `v*` tags, PR to `main`, Release published, `workflow_dispatch` (optional `version` input to override release version)
- **Environment**: `ubuntu-latest`, `timeout-minutes: 60`, concurrency control (from workflow source):

```yaml
concurrency:
  group: ci-&#123;&#123; github.ref &#125;&#125;
  cancel-in-progress: true
```

Where `&#123;&#123; github.ref &#125;&#125;` is the branch/tag reference for the push/PR; older runs on the same ref are cancelled.

- **Steps**:
  1. `actions/checkout@v6` (`fetch-depth: 0` for axion-release tag reading)
  2. `actions/setup-java@v5` (Temurin 17, `cache: gradle`)
  3. `docker compose -f docker-compose.test.yml up -d`, wait for Redis/MySQL/PostgreSQL/Elasticsearch (4 services) healthy (`timeout 180`)
  4. `./gradlew clean check jacocoRootReport --warning-mode=all` (unit tests + integration tests + aggregate coverage gate)
  5. `codecov/codecov-action@v5` uploads `build/reports/jacoco/jacocoRootReport/jacocoRootReport.xml` (`fail_ci_if_error: false`)
  6. Cleanup `docker compose -f docker-compose.test.yml down -v`

- **Publish to Maven Central** runs after step 4, triggered by any of: Release published / `workflow_dispatch` with version / `v*` tag push
  - Auth: `ORG_GRADLE_PROJECT_mavenCentralUsername/Password`, `centralPortalUsername/Password` (from secrets `CENTRAL_PORTAL_USERNAME` / `CENTRAL_PORTAL_TOKEN`), GPG in-memory key (`GPG_PRIVATE_KEY` / `GPG_PASSWORD`)
  - Command: `./gradlew publishAllPublicationsToMavenCentralRepository` (Vanniktech plugin, Central Portal); manual dispatch uses `-Pversion=` override, otherwise axion-release derives from `v*` tag

### 2. `docs.yml` (name: **Docs (GitHub Pages)**)

- **Triggers**: push to `main` with changes under `docs/**` or `.github/workflows/docs.yml`, and `workflow_dispatch`
- **Permissions**: `contents: read`, `pages: write`, `id-token: write`; concurrency group `pages`
- **Execution**: `actions/checkout@v7` → `actions/setup-node@v7` (Node 24, cache `docs/package-lock.json`) → `npm ci` → `npm run docs:build` (`DOCS_BASE=/<repo>/`) → `actions/upload-pages-artifact@v5` (`docs/.vitepress/dist`) → `actions/deploy-pages@v5`
- **Published URL**: https://cuihairu.github.io/redis-streaming/

## Local Equivalent Commands

```bash
# CI main flow (requires Docker)
docker compose -f docker-compose.test.yml up -d
./gradlew clean check jacocoRootReport
docker compose -f docker-compose.test.yml down -v

# Documentation site
cd docs && npm ci && npm run docs:build
```

## Related Configuration

- Coverage scope & gate: root `build.gradle` `jacocoRootReport` / `jacocoRootCoverageVerification`
- Codecov: `codecov.yml` and workflow's `codecov-action@v5`
- Build notes: root `CLAUDE.md`; test details: `TESTING.md`

---

**Version**: 0.2.0
**Last Updated**: 2026-10-05