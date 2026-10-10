# 多租户隔离与配额设计

本文供评审，不含代码改动。目标：当前代码中**没有任何租户概念**，多作业/多团队共用一套 Redis 时，命名冲突、资源争抢、故障域互相穿透。本设计给出命名空间隔离与配额的分阶段方案。

## 现状（事实盘点）

1. **零租户概念**：代码库无 `tenant` 字段/概念；`JobSpec`（`jobName/pipelineFactory/config/parallelism/version/description/updatedBy/updatedAt/specHash`）不带租户。
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

## 非目标

- 不做租户认证/鉴权体系（仍走控制面可插拔授权器；认证属于安全设计范畴）。
- 不做每租户独立连接池（Redisson 连接池按实例共享，租户级连接隔离属于方案 B 部署形态）。
- 不做租户级计费/计量报表（配额指标已可支撑，报表另提）。
