# 安装部署

本页给出最小上线要点与关键配置默认值。默认值均取自仓库源码（`MqOptions`、`RedisRuntimeConfig`、`RedisStreamingProperties`）。

## 1) 运行时要求
- Java 17+（构建脚本 `options.release = 17`）
- Redis：CI/测试环境使用 `redis:7-alpine`（见 `docker-compose*.yml`）；框架对 Redis 6 做了兼容处理（如 `source.redis.RedisStreamSource` 使用显式 `0-0` 而非需要 Redis ≥ 7.0 的 `StreamMessageId.MIN`）

## 2) Redisson 集成（推荐）
框架自身只提供单机简化的 `redis-streaming.redis.*` 配置（见 `spring-boot-starter` 的 `RedisStreamingProperties` javadoc）。生产环境集群/哨兵建议使用官方 redisson-spring-boot-starter，其版本应与本仓库依赖的 Redisson 对齐（`gradle/libs.versions.toml` 当前 `redisson = 4.7.0`；starter 类 javadoc 中的 `3.29.0` 为升级前的旧值）：
```gradle
implementation 'org.redisson:redisson-spring-boot-starter:<与 libs.versions.toml 中 redisson 一致的版本>'
```

Cluster 示例（redisson-cluster.yaml）：
```yaml
clusterServersConfig:
  nodeAddresses: ["redis://10.0.0.1:6379", "redis://10.0.0.2:6379"]
  password: your_pwd
  scanInterval: 2000
  connectTimeout: 10000
  timeout: 3000
```
application.yml：
```yaml
spring:
  redis:
    redisson:
      file: classpath:redisson-cluster.yaml
```

## 3) Starter 配置键（redis-streaming.*）

| 键 | 默认 | 说明 |
|---|---|---|
| `redis-streaming.redis.address` | `redis://127.0.0.1:6379` | 单机 Redis 地址（仅开发/测试） |
| `redis-streaming.registry.enabled` | `true`（缺省即生效） | 注册中心自动装配 |
| `redis-streaming.discovery.enabled` | `true`（缺省即生效） | 服务发现自动装配 |
| `redis-streaming.config.enabled` | `true`（缺省即生效） | 配置中心自动装配 |
| `redis-streaming.mq.enabled` | `true`（缺省即生效） | MQ 自动装配 |
| `redis-streaming.ratelimit.enabled` | `false`（需显式开启） | 限流自动装配 |
| `redis-streaming.registry.auto-register` | `true`（缺省即生效） | 自动注册本服务实例 |

（生效语义来自各 `@ConditionalOnProperty` 的 `matchIfMissing` 设置。）

## 4) MQ 消费端关键默认值（MqOptions）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `workerThreads` | `8` | 执行线程数 |
| `schedulerThreads` | `2` | 调度池（租约续约/rebalance/pending 扫描） |
| `maxInFlight` | `0`（0 = 不限制） | 全局 in-flight 并发上限（背压） |
| `maxLeasedPartitionsPerConsumer` | `0`（0 = 取 `workerThreads`） | 单实例可租 partitions 上限 |
| `claimIdleMs` | `300000`（5 分钟） | pending 条目 idle 超过该值才可被接管 |
| `claimBatchSize` | `50` | 每次接管批量 |
| `pendingScanIntervalSec` | `30` | pending 扫描间隔 |
| `renewIntervalSec` | `3` | 租约续期间隔 |
| `retryMaxAttempts` | `5` | 重试次数 |
| `retryBaseBackoffMs` | `1000` | 指数退避基数 |
| `retryMaxBackoffMs` | `60000` | 退避上限（封顶） |
| `retentionMaxLenPerPartition` | `100000` | 每分区流长上限（approximate trim） |
| `retentionMs` | `0`（0 = 不启用） | 基于时间的保留 |
| `trimIntervalSec` | `60` | 后台裁剪周期 |
| `ackDeletePolicy` | `none`（`none` / `immediate` / `all-groups-ack`） | ack 后删除策略 |

## 5) Redis runtime 关键默认值（RedisRuntimeConfig）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `pipelineParallelism` | `1` | 单进程子任务并行度 |
| `timerThreads` | `1` | processing-time timer 线程池 |
| `checkpointThreads` | `1` | checkpoint 调度/执行线程 |
| `checkpointDrainTimeout` | `30s` | checkpoint 前排空窗口（0/负值回退 30s） |
| `eventTimeTimerMaxSize` | `100000` | event-time timer 队列上限 |
| `windowMaxFiresPerRecord` | `256` | 每条消息最多 fire 的窗口数 |
| `watermarkOutOfOrderness` | `Duration.ZERO` | 乱序容忍（watermark = maxTs − outOfOrderness） |
| `mdcEnabled` | `false` | MDC 日志关联开关 |
| `mdcSampleRate` | `1.0`（0~1） | MDC 采样率 |

## 6) 可观测性
- 开启 Actuator + Prometheus；抓取 `/actuator/prometheus`
- 指标名前缀（由 spring-boot-starter 的 Micrometer collector/binder 注册）：
  - `redis_streaming_mq_*`（生产/消费/ack/重试/租约/保留裁剪/frontier）
  - `redis_streaming_runtime_*`（job/pipeline/handle 延迟、checkpoint、keyed state、window、watermark、timer 队列）
  - `redis_streaming_rl_*`（限流 allow/deny）
  - `redis_streaming_dlq_*`（DLQ 回放/删除/清理）
  - 完整清单见 [Metrics](/Metrics)
- Trace/日志关联：`RedisRuntimeConfig.mdcEnabled(true)` + `mdcSampleRate(0~1)`（MDC keys：`rs.job` / `rs.topic` / `rs.group` / `rs.consumer` / `rs.id` / `rs.key` / `rs.partition`）

## 7) 上线自检
- Redis 连通性/权限校验通过
- 消费者组分配均衡，pending 扫描/接管（claim）策略就绪（默认 idle 5 分钟起接管）
- DLQ 回放流程已演练，增长告警已配置（`redis_streaming_mq_dlq_total`、`redis_streaming_dlq_*`）

## 8) 多实例与滚动升级建议（要点）
- 同一 job 建议通过 consumer group 水平扩展；并结合 `MqOptions.maxLeasedPartitionsPerConsumer`（默认 0 = 取 `workerThreads`）避免超配 lease。
- checkpoint 为单进程 stop-the-world（不跨实例 barrier）；多实例部署时请优先使用幂等 sink 或 Redis-only 原子 sink 方案确保端到端效果一致性。
- 滚动升级：先扩容新版本实例，观察 leased partitions 与错误率稳定后，再逐步缩容旧版本实例。

## 9) 作业控制面（可选,声明式升级/回滚）
作业的提交、升级、回滚与停止可通过 Redis 控制面集中管理（设计与键布局见 [Control-Plane-Design.md](Control-Plane-Design.md),starter 装配见 [Spring-Boot-Starter.md](Spring-Boot-Starter.md) 的「作业控制面」）：

- **拓扑**：任一进程开启 `redis-streaming.runtime.control-plane.enabled=true` 作为提交入口（CLI/运维服务/应用皆可）；执行侧实例开启 `redis-streaming.runtime.agent.enabled=true`。控制面只写 spec（期望状态），agent 周期对账：认领（`SET NX EX`，TTL=max(3×poll,10s)）→ 部署 → 上报状态。
- **升级语义**：config/工厂/描述变化 = 全量升级（best-effort checkpoint → cancel → 重新部署，短暂中断）；仅并行度变化走 `scaleParallelism` 快速路径（不重启）。`rollback` 回到上一版本 spec。
- **多实例仲裁**：同一作业由认领键保证只在一个 agent 部署。agent 宕机后作业随进程消失（无自动故障转移）；认领 TTL 过期后在其余实例执行 `resume` 即可重新拉起。期望停止/重启以 `stop`/`resume` 为准。
- **滚动升级建议**：先在新实例 `resume`（认领会选中负载空闲的 agent）观察 `status:<job>` 稳定，再对旧实例 `stop`；回滚直接 `rollback`（spec 回退触发 agent 全量升级）。
- **审计**：操作与失败报告写入 `prefix + audit` 流（近似封顶 `audit-max-entries`），`tailAudit` 倒序读取;拒绝的授权也会留痕。
