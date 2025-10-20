# 快速开始

本页带你快速构建、测试并运行示例；完整细节请参阅根目录文档：
- QUICK_START.md、RUNNING_EXAMPLES.md、TESTING.md
- Spring Boot 入门：Spring-Boot-Starter.md

## 1) 环境准备
- Java 17+
- Docker（用于集成测试/示例中的 Redis）
- Gradle Wrapper（仓库自带）

## 2) 构建与单元测试
```bash
./gradlew clean build     # 构建所有模块并运行单测
# 或仅运行单测
./gradlew test
```

## 3) 集成测试（需要 Redis）
```bash
# 启动最小 Redis
docker-compose -f docker-compose.minimal.yml up -d

# 仅运行集成测试
./gradlew integrationTest

# 关闭容器
docker-compose -f docker-compose.minimal.yml down
```

提示
- 集成测试均使用 `@Tag("integration")` 标记，与单测分离。
- 运行单个测试类：
  ```bash
  ./gradlew :reliability:integrationTest --tests "RedisSlidingWindowRateLimiterIntegrationExample"
  ```

## 4) 运行示例
详见 RUNNING_EXAMPLES.md，典型步骤：
```bash
# 1) 启动依赖
docker-compose up -d

# 2) 运行某个示例（按需选择）
./gradlew :examples:run --args='mq-basic'
```

## 5) Spring Boot 集成（极简）
Gradle 依赖：
```gradle
implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.1.0'
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
  mq:
    enabled: true
```

指标（可选）：参考 Spring-Boot-Starter.md 的 Actuator/Prometheus 配置。

## 6) 常见问题
- 确认 Java 17：`java -version`
- 集成测试失败/卡住：检查 Redis 是否已启动（`redis-cli PING` 应返回 PONG）
- CI/CD 参考：docs/github-actions.md
