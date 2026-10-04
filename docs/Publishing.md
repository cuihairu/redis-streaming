# 发布流程

发布到 Maven Central（Central Portal）：
- 项目指南：[../PUBLISHING.md](../PUBLISHING.md)
- 发布说明：[maven-publish.md](./maven-publish.md)
- 迁移记录：[archive/MIGRATION_TO_CENTRAL_PORTAL.md](./archive/MIGRATION_TO_CENTRAL_PORTAL.md)

要点（取自根 `build.gradle` 与 `.github/workflows/ci.yml`）：
- 插件：`com.vanniktech.maven.publish`（0.29.0），`publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)` + `signAllPublications`
- 坐标：group `io.github.cuihairu.redis-streaming`，artifactId 为各模块名；`examples` 模块不发布
- 版本：由 axion-release 从 `v*` git 标签推导（`scmVersion`，tag 前缀 `v`）；`workflow_dispatch` 可用 `-Pversion=` 覆盖
- 触发：Release published / `v*` 标签推送 / 手动派发（见 `ci.yml` 的 Publish 步骤与凭据 secrets 约定）
- 凭据自检任务：`./gradlew checkCentralPortalCreds`（只打印凭据是否可见，不输出密钥）
