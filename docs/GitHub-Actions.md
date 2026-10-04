# CI/CD

本项目的 CI/CD 配置使用 GitHub Actions，工作流文件位于 `.github/workflows/`（可对照该目录下的 `README.md`）。

## 工作流清单（实际存在的 2 个）

### 1. `ci.yml`（名称：CI）
- **触发**：push 到 `main`、`v*` 标签、PR 到 `main`、Release published、`workflow_dispatch`（可输入 `version` 覆盖发布版本）
- **环境**：`ubuntu-latest`，`timeout-minutes: 60`，并发组 `ci-${{ github.ref }}`（`cancel-in-progress: true`）
- **步骤**：
  1. `actions/checkout@v6`（`fetch-depth: 0`，供 axion-release 读取标签）
  2. `actions/setup-java@v5`（Temurin 17，`cache: gradle`）
  3. `docker compose -f docker-compose.test.yml up -d`，等待 Redis/MySQL/PostgreSQL/Elasticsearch 共 4 个服务 healthy（`timeout 180`）
  4. `./gradlew clean check jacocoRootReport --warning-mode=all`（单测 + 集成测试 + 聚合覆盖率门禁）
  5. `codecov/codecov-action@v5` 上传 `build/reports/jacoco/jacocoRootReport/jacocoRootReport.xml`（`fail_ci_if_error: false`）
  6. 收尾 `docker compose -f docker-compose.test.yml down -v`
- **发布到 Maven Central**（步骤 4 之后，满足任一条件时执行）：Release published / `workflow_dispatch` 指定版本 / `v*` 标签推送
  - 认证：`ORG_GRADLE_PROJECT_mavenCentralUsername/Password`、`centralPortalUsername/Password`（来自 secrets `CENTRAL_PORTAL_USERNAME` / `CENTRAL_PORTAL_TOKEN`）、GPG 内存密钥（`GPG_PRIVATE_KEY` / `GPG_PASSWORD`）
  - 命令：`./gradlew publishAllPublicationsToMavenCentralRepository`（Vanniktech 插件，Central Portal）；手动派发时用 `-Pversion=` 覆盖，否则由 axion-release 从 `v*` 标签推导

### 2. `docs.yml`（名称：Docs (GitHub Pages)）
- **触发**：push 到 `main` 且改动落在 `docs/**` 或 `.github/workflows/docs.yml`，以及 `workflow_dispatch`
- **权限**：`contents: read`、`pages: write`、`id-token: write`；并发组 `pages`
- **步骤**：`actions/checkout@v7` → `actions/setup-node@v7`（Node 24，缓存 `docs/package-lock.json`）→ `npm ci` → `npm run docs:build`（`DOCS_BASE=/<repo>/`）→ `actions/upload-pages-artifact@v5`（`docs/.vitepress/dist`）→ `actions/deploy-pages@v5`
- **产物地址**：https://cuihairu.github.io/redis-streaming/

## 本地等价命令
```bash
# CI 主流程（需要 Docker）
docker compose -f docker-compose.test.yml up -d
./gradlew clean check jacocoRootReport
docker compose -f docker-compose.test.yml down -v

# 文档站
cd docs && npm ci && npm run docs:build
```

## 相关配置
- 覆盖率口径与门禁：根 `build.gradle` 的 `jacocoRootReport` / `jacocoRootCoverageVerification`
- Codecov：`codecov.yml` 与 workflow 中的 `codecov-action@v5`
- 构建说明：根目录 `CLAUDE.md`；测试细节：`TESTING.md`
