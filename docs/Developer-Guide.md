# 开发者指南

- 工程结构：多模块 Gradle（模块清单见 `settings.gradle`，共 20 个模块；见 [Architecture](/Architecture) 的模块表）
- 全量构建：`./gradlew build`（含单测与集成测试，`check` 依赖 `integrationTest`，需要 Redis）
- 只跑单测：`./gradlew test`；集成测试：`./gradlew integrationTest`
- 覆盖率门禁：根项目 `jacocoRootCoverageVerification`（聚合 INSTRUCTION ≥ 0.99），报告 `./gradlew jacocoRootReport`
- 代码规范：Java 17（`options.release = 17`）/ UTF-8 / 4 空格缩进；日志用 SLF4J；主代码开 `-Xlint:deprecation -Xlint:unchecked -Werror`（测试豁免 `-Werror` 与 unchecked）
- 版本管理：版本号由 axion-release 从 `v*` git 标签推导（根 `build.gradle` 的 `scmVersion`）
- 推荐阅读：[Architecture](/Architecture) / [Design](/Design) / [Spring-Boot-Starter](/Spring-Boot-Starter) / [CEP](/CEP)
