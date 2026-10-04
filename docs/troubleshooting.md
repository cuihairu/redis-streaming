# 故障排查

本页汇总构建、测试与运行期的常见问题。默认值均取自源码，测试流程见 [Testing](/Testing) 与根目录 `TESTING.md`。

## 构建

### 编译报「deprecation」错误
`build.gradle` 对主代码开启 `-Xlint:deprecation -Xlint:unchecked -Werror`（弃用即失败）；测试任务豁免 `-Werror` 与 `-Xlint:unchecked`。遇到此类失败说明主代码用到了 `@Deprecated` API，需要升级调用方式，不建议关闭门禁。

### 测试 OOM
`build.gradle` 把所有 `Test` 任务的 `maxHeapSize` 设为 `1536m`（套件变大后 512m 默认值会在 `:mq:test` OOM）。若仍内存不足，检查机器可用内存或单模块运行。

## 测试

### 集成测试报 Connection refused
集成测试需要可用的 Redis：

```bash
docker compose -f docker-compose.minimal.yml up -d
docker exec streaming-redis-test redis-cli ping   # 应返回 PONG
./gradlew integrationTest
```

- 地址取 `REDIS_URL` 环境变量，缺省 `redis://127.0.0.1:6379`。
- 全量环境（Redis + MySQL + PostgreSQL + Elasticsearch）用 `docker compose -f docker-compose.test.yml up -d`（与 CI 一致，容器名 `streaming-redis-test` 等，等待健康检查通过）。

### 只想跑单测，却报集成测试失败
`build` 依赖 `check`，而 `check` 包含 `integrationTest`。没有 Redis 时只跑单测：

```bash
./gradlew test
```

### 集成测试偶发互扰
`build.gradle` 注册了共享 BuildService（`sharedRedisIntegrationTestService`，`maxParallelUsages = 1`），各模块的 `integrationTest` 在并行构建下也会串行执行，以共用同一个 Redis 实例。若手工同时起两套环境，可能出现状态串味，先 `docker compose down -v` 再启动。

### CDC 集成测试「没跑」
- 生命周期/并发用例跑在内嵌 H2 上，无需外部服务。
- MySQL/PostgreSQL 连接器用例（`DatabasePollingCDCConnectorIntegrationTest` 等）依赖 `MYSQL_URL` / `POSTGRES_URL`，未设置时经 JUnit assumption 跳过（不是失败）。触发方式见 [Testing](/Testing)。

## 运行期

### 消息迟迟不被重新消费（pending 积压）
pending 扫描按 idle 时长判定接管：`MqOptions.claimIdleMs` 默认 **300000ms（5 分钟）**，`pendingScanIntervalSec` 默认 30s。慢 handler 场景下 5 分钟内看到同一消息 pending 是预期行为；调小 `claimIdleMs` 会提高重复投递概率（builder 已钳到 ≥1ms）。

### 重试风暴 / 退避异常
指数退避基数 `retryBaseBackoffMs` 默认 1000ms、封顶 `retryMaxBackoffMs` 默认 60000ms，饱和计算不会溢出为负值。若观察到高频重试，检查是否 `retryMaxAttempts`（默认 5）被调大且 handler 持续失败——失败条目会进 DLQ。

### DLQ 增长
关注指标 `redis_streaming_mq_dlq_total` 与 `redis_streaming_dlq_*`（回放/删除/清理）；DLQ 条目在 handler 持续失败期间会留在流中（XACK 只清 PEL），可人工处置后回放。

### 内存持续增长（流保留）
`retentionMaxLenPerPartition` 默认 100000、`trimIntervalSec` 默认 60s、`retentionMs` 默认 0（按时间裁剪未启用）。积压超出预期时按需收紧这三个配置，或为 topic 单独配置 DLQ 保留（`dlqRetentionMaxLen` / `dlqRetentionMs`）。

### 排查现场缺上下文
打开 MDC 日志关联：`RedisRuntimeConfig.mdcEnabled(true)`（默认 `false`）+ `mdcSampleRate`（默认 1.0，0~1），MDC keys 为 `rs.job` / `rs.topic` / `rs.group` / `rs.consumer` / `rs.id` / `rs.key` / `rs.partition`。

## 文档站

### 站点 404 / 链接打不开
GitHub Pages 路径**大小写敏感**：链接目标必须与实际文件名一致（例如 `Architecture.md`，不是 `ARCHITECTURE.md`；`GitHub-Actions.md`，不是 `github-actions.md`）。

### 本地预览
```bash
cd docs
npm ci
npm run docs:dev
# http://localhost:5173（VitePress 默认端口）
```

## 参考
- 英文版：[en/Troubleshooting-en.md](./en/Troubleshooting-en.md)
- 测试排障细节：根目录 `TESTING.md` 与 [Testing](/Testing)
- CI 行为（健康检查等待、发布条件）：[GitHub Actions](/GitHub-Actions)
