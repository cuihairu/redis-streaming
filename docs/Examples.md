# Examples

模块：`examples/`

可运行示例与最小样例，覆盖 mq / registry / aggregation / streaming / state / checkpoint / window / ratelimit / springboot 等子模块。

## 运行方式
```bash
# 依赖（默认连接 redis://127.0.0.1:6379）
docker compose up -d redis

# 默认入口：registry.ServiceRegistryExample
./gradlew :examples:run

# 指定入口
./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.mq.MessageQueueExample
```
（`examples/build.gradle` 的 `application.mainClass` 由 `-PmainClass` 覆盖。）

## 可用入口（`examples/src/main/java/io/github/cuihairu/redis/streaming/examples`）
- `registry.ServiceRegistryExample`（默认）
- `registry.CustomPrefixExample`
- `mq.MessageQueueExample`
- `aggregation.StreamAggregationExample`
- `streaming.ComprehensiveStreamingExample`
- `state.StateExample`
- `checkpoint.CheckpointExample`
- `window.WindowExample`
- `ratelimit.RateLimitExample`
- `springboot.StarterExampleApplication`（Spring Boot Starter 示例，配置见 `examples/src/main/resources/application.yml`）

## 参考
- 根目录 `RUNNING_EXAMPLES.md`（完整步骤与环境变量）
- 该模块不参与发布（根 `build.gradle` 对 `examples` 跳过 maven-publish 插件）
