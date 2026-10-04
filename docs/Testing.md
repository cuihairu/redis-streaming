# 测试指南

本页汇总本地与 CI 环境下的单测/集成测试做法。详细说明见根目录 `TESTING.md`。

## 1) 单元测试
```bash
./gradlew test                  # 运行所有模块的单测
./gradlew :core:test            # 只运行某个模块
./gradlew :core:test --tests "ClassNameTest"   # 只跑某个测试类
```

说明
- 单元测试不得依赖 Redis（`test` 任务排除 `@Tag("integration")`）。
- 覆盖率门禁（JaCoCo）：根项目 `jacocoRootCoverageVerification` 对聚合 INSTRUCTION 覆盖率要求 **≥ 0.99**（`build.gradle` 的 `minimum = 0.99`；`**/kafka/**`、MySQL binlog、PostgreSQL 逻辑复制等无法脱离外部服务执行的连接器类不计入）。`./gradlew check` 会执行该门禁，`./gradlew jacocoRootReport` 产出聚合报告（XML：`build/reports/jacoco/jacocoRootReport/jacocoRootReport.xml`）。
- 单测 `maxHeapSize = 1536m`。

## 2) 集成测试（需 Redis）
```bash
# 启动最小 Redis
docker compose -f docker-compose.minimal.yml up -d

# 仅运行集成测试
./gradlew integrationTest

# 关闭容器
docker compose -f docker-compose.minimal.yml down
```

说明
- 集成测试统一使用 `@Tag("integration")` 标注，默认不随 `test` 执行；地址取 `REDIS_URL`（缺省 `redis://127.0.0.1:6379`）。
- 跑单个类：
  ```bash
  ./gradlew :reliability:integrationTest --tests "RedisSlidingWindowRateLimiterIntegrationExample"
  ```
- 完整环境（Redis + MySQL + PostgreSQL + Elasticsearch，与 CI 相同）：
  ```bash
  docker compose -f docker-compose.test.yml up -d
  ./gradlew clean check
  docker compose -f docker-compose.test.yml down -v
  ```
- 各模块 `integrationTest` 通过共享 BuildService 串行执行（`maxParallelUsages = 1`），避免并行争用同一 Redis。

## 3) CI 提示
- 确认 Java 17（`java -version`）；CI 使用 `actions/setup-java@v5` + Temurin 17。
- CI 先起 `docker-compose.test.yml` 并等待 4 个服务 healthy（timeout 180s），再执行 `./gradlew clean check jacocoRootReport`。
- 某些 Redis 时序敏感用例可适当增加等待或重试，详见 [GitHub Actions](/GitHub-Actions)。
