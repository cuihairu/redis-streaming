# 快速开始

本页覆盖构建、测试与示例运行；完整细节见根目录文档：
- [QUICK_START.md](https://github.com/cuihairu/redis-streaming/blob/main/QUICK_START.md)、[RUNNING_EXAMPLES.md](https://github.com/cuihairu/redis-streaming/blob/main/RUNNING_EXAMPLES.md)、[TESTING.md](https://github.com/cuihairu/redis-streaming/blob/main/TESTING.md)
- Spring Boot 入门：[Spring-Boot-Starter](/Spring-Boot-Starter) 与 [spring-boot-starter-guide](/spring-boot-starter-guide)

## 1) 环境准备
- Java 17+（构建脚本将 `options.release` 固定为 17）
- Docker（用于集成测试/示例中的 Redis；本仓库脚本使用 `docker compose` V2）
- Gradle Wrapper（仓库自带，Gradle 8.5）

## 2) 构建与测试
```bash
./gradlew build     # 构建所有模块；build 依赖 check，会同时跑单测与集成测试
# 只跑单测（不含集成测试，不需要 Redis）
./gradlew test
```

注意：`build`/`check` 会执行 `integrationTest`（需要可用的 Redis），单机没有 Redis 时请先执行第 3 步或只跑 `./gradlew test`。

## 3) 集成测试（需要 Redis）
```bash
# 启动最小 Redis（docker-compose.minimal.yml，redis:7-alpine）
docker compose -f docker-compose.minimal.yml up -d

# 仅运行集成测试
./gradlew integrationTest

# 关闭容器
docker compose -f docker-compose.minimal.yml down
```

提示
- 集成测试均使用 `@Tag("integration")` 标记，`test` 任务默认排除它们。
- 运行单个测试类：
  ```bash
  ./gradlew :reliability:integrationTest --tests "RedisSlidingWindowRateLimiterIntegrationExample"
  ```
- 集成测试默认连接 `REDIS_URL`（缺省 `redis://127.0.0.1:6379`）。

## 4) 运行示例
详见根目录 RUNNING_EXAMPLES.md，典型步骤：
```bash
# 1) 启动依赖
docker compose up -d

# 2) 运行某个示例（通过 -PmainClass 指定入口，默认入口是 registry.ServiceRegistryExample）
./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.mq.MessageQueueExample
```
可用的示例入口见 [Examples](/Examples)。

## 5) Spring Boot 集成（最小配置）
Gradle 依赖（当前 0.2.0）：
```gradle
implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.2.0'
```

在应用中启用：
```java
@SpringBootApplication
@EnableRedisStreaming
public class Application {
  public static void main(String[] args){ SpringApplication.run(Application.class, args); }
}
```

application.yml（最小）：
```yaml
spring:
  application:
    name: demo
redis-streaming:
  redis:
    address: redis://127.0.0.1:6379   # 默认值，可省略
  mq:
    enabled: true                      # 缺省即为 true（matchIfMissing）
```

指标（可选）：参考 [Metrics](/Metrics) 的 Actuator/Prometheus 配置。

## 6) 常见问题
- 确认 Java 17：`java -version`
- 集成测试失败/卡住：检查 Redis 是否已启动（`redis-cli PING` 应返回 PONG）
- CI/CD 参考：[GitHub Actions](/GitHub-Actions)
