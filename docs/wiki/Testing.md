# 测试指南

本页汇总本地与 CI 环境下的单测/集成测试做法。详细说明见 TESTING.md。

## 1) 单元测试
```bash
./gradlew test                  # 运行所有模块的单测
./gradlew :core:test            # 只运行某个模块
./gradlew :core:test --tests "ClassNameTest"   # 只跑某个测试类
```

说明
- 单元测试不得依赖 Redis。
- 推荐覆盖率（JaCoCo）：core ≥ 80%，其他模块 ≥ 70%。

## 2) 集成测试（需 Redis）
```bash
# 启动最小 Redis
docker-compose -f docker-compose.minimal.yml up -d

# 仅运行集成测试
./gradlew integrationTest

# 关闭容器
docker-compose -f docker-compose.minimal.yml down
```

说明
- 集成测试统一使用 `@Tag("integration")` 标注，默认不随 `test` 执行。
- 跑单个类：
  ```bash
  ./gradlew :reliability:integrationTest --tests "RedisSlidingWindowRateLimiterIntegrationExample"
  ```

## 3) CI 提示
- 确认 Java 17（`java -version`）。
- 依赖组件建议复用仓库内的 Docker Compose。
- 某些 Redis 时序敏感用例可适当增加等待或重试，详见 docs/github-actions.md。
