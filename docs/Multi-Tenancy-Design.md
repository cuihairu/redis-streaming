# 多租户隔离与配额设计

本文供评审，不含代码改动。目标：当前代码中**没有任何租户概念**，多作业/多团队共用一套 Redis 时，命名冲突、资源争抢、故障域互相穿透。本设计给出命名空间隔离与配额的分阶段方案。

## 现状（事实盘点）

1. **租户概念（v1 已落地 2026-10-11）**：`JobSpec.tenant`（默认 `StreamKeys.DEFAULT_TENANT`）、`MqOptions.tenant`、`AuditEntry.tenant`、`TenantQuotaPolicy` 均已存在；本设计剩余项（线程/内存/in-flight 配额、指标 tenant 维度）待实施——其余模块的 key 前缀租户注入见下方「落地记录」。
2. **全局静态前缀**：`mq` 的 `StreamKeys.configure(controlPrefix, streamPrefix)` 是 `static volatile` 全局量（默认 `streaming:mq` / `stream:topic`），所有 MQ key（分区流、DLQ、meta、lease、retry、commit frontier、topics registry）由它派生。**多作业共用 JVM 时是全局配置；跨 JVM 部署时若两个应用不 configure 不同前缀，同名 topic 直接共用同一条 stream**。
3. **runtime key**：checkpoint/dedup 走 `streaming:runtime:*` 前缀（可配）；keyed state key 带 job 维度（`{stateKeyPrefix}:{job}:...`），job 级隔离天然存在，但 stateKeyPrefix 本身是全局静态。
4. **背压/配额粒度**：`maxInFlight` 等内存闸门是 per-consumer-instance 的；**没有任何跨租户的速率/容量配额**（无生产速率限制、无 stream 长度配额——autoTrim 只按 topic 配置裁剪）。
5. **可观测性**：指标无 tenant/instance 维度（见指标统一设计）；控制面审计有 actor/job，无 tenant。
6. 复用件：`reliability` 已有 `RateLimiter`（Redis 令牌桶，可按 key 限流）；控制面已有 spec 存储 + 授权器钩子 + 审计。

## 问题

- 同名 topic/作业跨团队冲突：stream、DLQ、lease、控制面 spec 全部同名互踩。
- 一个租户的生产洪峰直接吃掉共享 stream/连接池/消费者吞吐，其他租户受牵连（故障域与资源域不隔离）。
- 无法按租户限额（速率、分区数、stream 长度、checkpoint 频率），也无法按租户审计与计量。

## 可选方案

### 方案 A：命名空间隔离 + Redis 令牌桶配额（推荐）

**隔离 = key 前缀注入，配额 = 复用 RateLimiter，不新建基础设施。**

- **租户标识**：`JobSpec` 加 `tenant` 字段（默认 `default`）；MQ 的 `MqOptions` 加 `tenant`；启动时 `StreamKeys.configure(controlPrefix, streamPrefix)` 改为**按租户派生**：`{tenantPrefix}:{tenant}:{原key}`，或等价地让 `StreamKeys` 接受实例化配置（每个 producer/consumer/env 持有自己的 `StreamKeys` 视图，替代 static volatile——static volatile 保留为单租户兼容入口）。
- **控制面**：spec 键空间按 tenant 分段（`...:{tenant}:jobs`），授权器拿到 `(tenant, actor, op)` 三元组判定；审计加 tenant 维度。
- **配额（v1 只做这三项，够了）**：
  1. **生产速率**：producer send 前过 `RateLimiter`（key=`tenant:topic`，超限默认快速失败 + 指标，可选阻塞）。
  2. **stream 容量**：autoTrim 上限按租户配置（防一个 topic 无限涨）。
  3. **并行度/分区总数**：控制面创建 spec 时校验租户配额（用 Redis 计数器登记租户已用 parallelism/分区数）。
- **可观测**：指标加 `tenant` 维度（与指标统一设计的维度规范合并落地）。

### 方案 B：每租户独立 Redis（DB 或实例）

隔离最彻底（网络/内存/故障域全断开），但连接管理、控制面跨租户视图、运维成本都翻倍。适合作为**大客户的部署形态**（文档支持，`tenant → Redis URL` 映射由接入方做），框架 v1 不内置。

### 方案 C：纯命名约定（topic 叫 `tenant:topic`），零代码

最小改动，但没有强制力（前缀拼错就穿），配额与审计仍然缺失。作为方案 A 未落地前的**文档口径**先顶。

## 迁移路径（方案 A 分期）

1. **第 1 步**：`StreamKeys` 实例化（保留 static 入口兼容），`MqOptions`/`JobSpec` 加 `tenant` 字段，key 派生函数加 tenant 段；默认 tenant=`default` 时 key 与现状完全一致（**零迁移成本**）。
2. **第 2 步**：生产速率配额（RateLimiter 挂 producer）+ autoTrim 租户上限；指标加 tenant 维度。
3. **第 3 步**：控制面按 tenant 分段 spec 键空间 + 配额校验（分区/并行度）+ 审计维度。

## 落地记录：key 前缀租户注入（2026-10-15）

统一契约落在 `core` 的 `Tenant`：默认租户 `default` 不产生任何键段（与旧版键**逐字节一致**，零迁移），非默认租户在**键前缀之后**插入单个 `:tenant` 段；租户名必须匹配 `[A-Za-z0-9._-]{1,64}`（不含冒号），非法值在构造/启动时抛 `IllegalArgumentException`，不会静默降级成共享键空间。

| 模块 | 注入点 | 键示例 |
|---|---|---|
| mq | `StreamKeys(prefix, streamPrefix, tenant)`（v1 已有） | `streaming:mq:acme:...` |
| registry | `RegistryKeys(keyPrefix, tenant)` / `BaseRedisConfig.setTenant`；Lua 脚本收到**生效前缀**（前缀 + 租户段） | `redis_streaming_registry:acme:services:{svc}:instance:{id}` |
| config | `ConfigServiceConfig.formatKey` | `redis_streaming:acme:config:{group}:{dataId}` |
| state / checkpoint / sink 去重 | `RedisRuntimeConfig.Builder.tenant`，在 `build()` 时统一派生 | `streaming:runtime:acme:...`、`streaming:runtime:checkpoint:acme:{job}:...` |
| starter | `redis-streaming.mq.tenant`、`redis-streaming.registry.tenant`、`redis-streaming.config.tenant` | — |

三点需要注意：

- **调用顺序无关**：`Builder.tenant(...)` 只记录租户，键段在 `build()` 时派生，因此自定义 `stateKeyPrefix`/`checkpointKeyPrefix`/`sinkDedupKeyPrefix` 同样会被加上租户段，且不受 setter 先后顺序影响（此前「自定义前缀获胜」会让租户被静默丢弃，即两个租户共用一套状态键）。
- **重投锁曾漏租户段**：`RedisMessageConsumer` 的重投 mover 此前把锁键写死为 `streaming:mq:retry:lock:{topic}`，既绕过控制前缀也绕过租户段——两个租户用同名 topic 会互相串行（一方的重投阻塞另一方）。现由 `StreamKeys.retryLockKey(topic)` 派生，默认租户键与旧版逐字节一致。
- **cdc 无需改动**：CDC 只投递到 MQ topic，而 topic 键已按租户分段；sink 的目标键由调用方显式给出，框架托管的去重键前缀已随 `RedisRuntimeConfig.Builder.tenant` 分段。

真实 Redis 上的隔离验证（`@Tag("integration")`）：registry 与 config 各一份「两租户同 group/dataId 不互读 + 默认租户保持旧键形」用例，runtime 一份「同一作业名、同一自定义前缀下两租户的状态键与检查点互不可见」用例（`RedisRuntimeTenantIsolationIntegrationTest`），mq 用例钉住重投锁键按租户分段。

## 非目标

- 不做租户认证/鉴权体系（仍走控制面可插拔授权器；认证属于安全设计范畴）。
- 不做每租户独立连接池（Redisson 连接池按实例共享，租户级连接隔离属于方案 B 部署形态）。
- 不做租户级计费/计量报表（配额指标已可支撑，报表另提）。
