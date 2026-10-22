# 多租户隔离与配额设计

本文供评审，不含代码改动。目标：当前代码中**没有任何租户概念**，多作业/多团队共用一套 Redis 时，命名冲突、资源争抢、故障域互相穿透。本设计给出命名空间隔离与配额的分阶段方案。

## 现状（事实盘点）

1. **租户概念（v1 已落地 2026-10-11）**：`JobSpec.tenant`（默认 `StreamKeys.DEFAULT_TENANT`）、`MqOptions.tenant`、`AuditEntry.tenant`、`TenantQuotaPolicy` 均已存在；本设计剩余项的**已代决**口径（2026-10-16）：in-flight 配额已落地（见下方「v2 配额增量」）、指标 tenant 维度已落地、线程配额与内存配额不新增维度（理由见该节）、租户级迁移与运维 CLI 已落地（见下方「落地记录：租户键迁移引擎」）——其余模块的 key 前缀租户注入见下方「落地记录」。
2. **全局静态前缀**：`mq` 的 `StreamKeys.configure(controlPrefix, streamPrefix)` 是 `static volatile` 全局量（默认 `streaming:mq` / `stream:topic`），所有 MQ key（分区流、DLQ、meta、lease、retry、commit frontier、topics registry）由它派生。**多作业共用 JVM 时是全局配置；跨 JVM 部署时若两个应用不 configure 不同前缀，同名 topic 直接共用同一条 stream**。
3. **runtime key**：checkpoint/dedup 走 `streaming:runtime:*` 前缀（可配）；keyed state key 带 job 维度（`{stateKeyPrefix}:{job}:...`），job 级隔离天然存在，但 stateKeyPrefix 本身是全局静态。
4. **背压/配额粒度**：`maxInFlight` 等内存闸门是 per-consumer-instance 的；**没有任何跨租户的速率/容量配额**（无生产速率限制、无 stream 长度配额——autoTrim 只按 topic 配置裁剪）。〔2026-10-16 已代决增补：跨租户**声明式** in-flight 配额已落地（见「v2 配额增量」），运行期闸门仍为 per-consumer。〕
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
| sink 数据面 | `RedisListSink` / `RedisStreamSink` / `RedisHashSink` 租户构造器，复用 `Tenant.scopedKey` 解析 `prefix:tenant:name`（测试先行 c9cb60e，落地 5a9847e）；List/Stream 空前缀=裸名原样，HashSink 空前缀回退默认前缀 `app`；旧构造器键形逐字节不变 | `app:acme:orders` |
| starter | `redis-streaming.mq.tenant`、`redis-streaming.registry.tenant`、`redis-streaming.config.tenant` | — |

四点需要注意：

- **调用顺序无关**：`Builder.tenant(...)` 只记录租户，键段在 `build()` 时派生，因此自定义 `stateKeyPrefix`/`checkpointKeyPrefix`/`sinkDedupKeyPrefix` 同样会被加上租户段，且不受 setter 先后顺序影响（此前「自定义前缀获胜」会让租户被静默丢弃，即两个租户共用一套状态键）。
- **重投锁曾漏租户段**：`RedisMessageConsumer` 的重投 mover 此前把锁键写死为 `streaming:mq:retry:lock:{topic}`，既绕过控制前缀也绕过租户段——两个租户用同名 topic 会互相串行（一方的重投阻塞另一方）。现由 `StreamKeys.retryLockKey(topic)` 派生，默认租户键与旧版逐字节一致。
- **DLQ 管理面曾漏租户段**：`RedisDeadLetterAdmin.listTopics()` 的 SCAN 用 `DlqKeys.dlq()`（不含租户段），而 DLQ 数据面写 `StreamKeys.dlqKey()`（含租户段）——租户部署下扫到 `acme:orders:dlq` 会把 topic 读成 `acme:orders`，随后的 size/list/replay 全部落到不存在的 `acme:acme:orders:dlq`（静默空转）。现改为跟随 service 的键视图（`RedisDeadLetterService.keys()`，与数据面同一来源），默认租户行为逐字节不变。
- **starter MQ 管理面 bean 曾漏租户视图**：starter 的 `messageQueueAdmin` / `deadLetterService` / `deadLetterConsumer` / `deadLetterQueueManager` 四个 bean 此前各取 `StreamKeys.shared()`（进程级静态租户）。该静态租户只由 `MessageQueueFactory` 构造器副作用设置，依赖 bean 创建顺序；用户自带 factory bean（`@ConditionalOnMissingBean` 的正常后门）时完全不设置，四个 bean 全部落到 `default` 租户——`redis-streaming.mq.tenant` 静默失效，租户数据错写共享命名空间。现各 bean 自行从 `MqOptions` 建租户 key 视图（`StreamKeys.of(opts)` / `RedisMessageQueueAdmin(client, opts)`），与创建顺序无关；默认租户键逐字节不变。
- **cdc 无需改动**：CDC 只投递到 MQ topic，而 topic 键已按租户分段；sink 的目标键默认仍由调用方显式给出（旧构造器零变化），可选的租户构造器把键写成 `prefix:tenant:name`（见上表 sink 数据面行），框架托管的去重键前缀已随 `RedisRuntimeConfig.Builder.tenant` 分段。

真实 Redis 上的隔离验证（`@Tag("integration")`）：registry 与 config 各一份「两租户同 group/dataId 不互读 + 默认租户保持旧键形」用例，runtime 一份「同一作业名、同一自定义前缀下两租户的状态键与检查点互不可见」用例（`RedisRuntimeTenantIsolationIntegrationTest`），mq 用例钉住重投锁键按租户分段。

## 落地记录：v2 配额增量（2026-10-16，已代决）

**声明式 in-flight 配额**（内存闸门维度）：`JobSpec.maxInFlight` 声明每个消费者的 in-flight 上限（0 = 运行期默认，即无界），控制面按租户校验 `Σ(parallelism × maxInFlight) ≤ maxTotalInFlightPerTenant`（`TenantQuotaPolicy` 新增维度，0 = 不限，默认策略不变即零迁移），提交与升级时同步拒收**未声明**的作业——无界作业无法计入，放行等于让配额失效。声明经 `RedisJobLauncher.configFor` 透传到 `MqOptions.maxInFlight`，由既有 per-consumer Semaphore 执行；`maxInFlight` 已进入 `specHash` 语义输入，只改声明也会触发作业漂移检测。**配置面**：配额三项均可由 starter 属性直接绑定（`redis-streaming.runtime.control-plane.max-jobs-per-tenant` / `max-total-parallelism-per-tenant` / `max-total-in-flight-per-tenant`，默认全 0 = 不限，零迁移），控制面 Bean 经 `RedisJobControlPlane.quotaPolicy()` 暴露生效策略；此前三项配额仅在 Java API 面可用，本批补齐 YAML 配置面。

代决口径与理由（事后用户可推翻）：

- **线程配额不新增维度**：本引擎每条并行子任务配一条消费线程，线程数 ≡ `parallelism`，已被 v1 的 `maxTotalParallelismPerTenant` 覆盖；再加一个「线程配额」只是同量别名，徒增配置面而不增加约束力。
- **内存配额以 in-flight 上限代理**：不做真实堆/字节计量——消息大小无上界、跨 JVM 无法归集，真实计量只能靠采样估算，误差大于收益；`in-flight × 消费线程数` 已给出可执行且可解释的内存上界。
- **租户级迁移与运维 CLI**：承载面已代决定为挂 `reliability`，迁移引擎、家族表与 CLI 入口均已落地（见下方「落地记录：租户键迁移引擎」；CLI 入口原列「待真实运维场景再立项」，2026-10-16 第二轮代决认定其解锁条件并非外部条件、属排期类，撤销搁置直接落地）。

## 落地记录：租户键迁移引擎（2026-10-16，已代决）

租户段插在键前缀**之后**，所以一族键的租户收编（或退出）就是一次纯**前缀搬迁**：`stream:topic:orders:p:0 → stream:topic:acme:orders:p:0`。引擎落在 `reliability` 的 `TenantKeyMigrator`，承载面选 `reliability`（`core` 已 `api` 暴露 Redisson，不引入新分层边）；引擎**只搬键**，不认识模块语义，搬迁清单由调用方给出（默认清单见下表）。

语义要点：

- **原子且零损**：每键一条 `RENAMENX`，payload、键类型、TTL 原样保留，没有半搬状态；目标键已存在**拒绝覆盖**（记 skipped，源键留在原地），绝不 force rename。
- **dry-run 默认**：`MigrationOptions.dryRun` 默认 `true`，只扫只数不动键，且同样探测目标碰撞，所以试跑就能看出真跑是否干净。
- **可重入**：搬迁按键幂等，中断后重跑只搬仍在源前缀下的键；一族搬完后重跑是干净 no-op。
- **嵌套目标不再二段收编**：收编方向 `stream:topic:` → `stream:topic:acme:` 时目标命名空间落在源扫描模式**内部**，已在目标前缀下的键视为已迁移并跳过（否则重跑会把 `acme:` 段叠成两层）。回滚方向目标不嵌套，无需特判。
- **重叠家族最长前缀归属**：`streaming:runtime:` 与 `streaming:runtime:checkpoint:` 等前缀互相嵌套时，一个键归**最长**匹配的源前缀所有，只搬一次、绝不搬错家。
- **坏搬迁单失败关闭**：清单里出现重复源前缀或恒等搬迁（源=目标）时整单拒跑，连扫描都不做——一个家族的笔误不可能顺带搬动另一个家族。
- **扫描在搬迁之前**：一族键先收集完再逐键 rename，因为 rename 会改动 SCAN 正走着的键空间。
- **先停写者**：迁移期间旧命名空间仍在写入的键会落在旧空间、切换后不可见；作业先停、族键搬完、再切配置。

各家族默认搬迁对（`tenant = acme` 示例；租户段取 `Tenant` 契约，非法租户名在建搬迁对前即抛错）。表中前缀是各模块源码里的**真实默认值**，已固化进 `TenantKeyFamilies`（CLI `--family` 取第一列的名字，`--prefix` 可覆盖第二列）：

| 家族（CLI 名） | 源前缀 | 收编目标前缀 |
|---|---|---|
| mq-control | `streaming:mq` | `streaming:mq:acme` |
| mq-data | `stream:topic` | `stream:topic:acme` |
| runtime-state | `streaming:runtime` | `streaming:runtime:acme` |
| checkpoint | `streaming:runtime:checkpoint:` | `streaming:runtime:checkpoint:acme:` |
| sink-dedup | `streaming:runtime:sinkDedup:` | `streaming:runtime:sinkDedup:acme:` |
| control-plane | `streaming:runtime:control:` | `streaming:runtime:control:acme:` |
| registry | `redis_streaming_registry` | `redis_streaming_registry:acme` |
| config | `redis_streaming` | `redis_streaming:acme` |

两个形状细节：

- **尾冒号两态**：mq / runtime 状态 / registry / config 四族前缀**不带**尾冒号（消费方自拼冒号），checkpoint / sink 去重 / 控制面三族**带**尾冒号（runtime config 直接拼租户段收尾）。`TenantKeyFamilies.scopedPrefix` 对两态各给正确拼法；机械用 `Tenant.scopedPrefix` 会给带冒号前缀拼出 `checkpoint::acme` 双冒号。
- **两处嵌套**：runtime 状态是 checkpoint / sink 去重 / 控制面三行的字符串前缀；**config（`redis_streaming`）是 registry（`redis_streaming_registry`）的字符串前缀**。嵌套对必须**同单搬迁**，引擎按最长前缀归属分发；只搬宽前缀会把检查点搬到 `streaming:runtime:acme:checkpoint:...`、把 registry 键搬到 `redis_streaming:acme_registry:...` 的错误位置。CLI 对会吞没未选家族的 `--family` 子集**拒跑**（列出被吞家族，`--allow-overlap` 显式放行）。退出租户段就是同一张表左右两列对调（`--dir out`）。

源前缀必须**精确圈定要搬的族**：宽前缀的扫描会同时命中所有已带租户段的键，收编前先跑 dry-run 核对 `wouldMove` 计数；若存量键空间里本就存在与租户同名的段（如 topic 恰叫 `acme`），dry-run 的 `wouldMove` 偏少就是信号。

### 运行入口（CLI，`reliability` 的 `TenantKeyMigrationCli`）

```
java -cp <依赖> io.github.cuihairu.redis.streaming.reliability.migration.TenantKeyMigrationCli   --tenant acme                      # dry run，全部 8 族，默认 redis://127.0.0.1:6379
  --tenant acme --apply              # 真搬
  --tenant acme --dir out --apply    # 回滚（租户段搬出）
  --tenant acme --family mq-data     # 子集（嵌套对子集会被拒跑）
  --tenant acme --prefix mq-data=myapp:stream   # 定制过前缀的部署
```

dry-run 默认、`--scan-count` 透传 SCAN COUNT；退出码 `0` 干净 / `1` 不干净（碰撞或逐键失败）/ `2` 用法错。运维顺序不变：先停作业与写者 → 搬键 → 切租户配置。

验证：`TenantKeyMigratorTest` 16 单测（mock `RKeys`：前缀校验、失败关闭、dry-run 默认、碰撞拒绝、逐键失败隔离、嵌套归属、嵌套目标不重搬）+ `TenantKeyMigrationCliTest` 21 单测（解析全旗标与全错误、退出码、吞没拒跑与放行、方向与定制前缀、help 不连 Redis）+ `TenantKeyFamiliesTest` 16 单测（表真值、收编/退出互逆、租户契约、定制覆盖、两处嵌套吞没判定）+ `TenantKeyMigratorIntegrationTest` 6 集成 + `TenantKeyMigrationCliIntegrationTest` 6 集成（真实 Redis：CLI 端到端收编保 TTL/payload、dry-run 原样、二次跑 no-op、回滚、碰撞退出码 1、吞没拒跑不连接）。

至此本设计**无剩余项**：迁移引擎、家族表与运行入口均已落地（原「待真实运维场景再立项」的 CLI 入口于 2026-10-16 第二轮代决撤销搁置并落地，理由：解锁条件并非外部条件，属排期类可代决）。

## 非目标

- 不做租户认证/鉴权体系（仍走控制面可插拔授权器；认证属于安全设计范畴）。
- 不做每租户独立连接池（Redisson 连接池按实例共享，租户级连接隔离属于方案 B 部署形态）。
- 不做租户级计费/计量报表（配额指标已可支撑，报表另提）。
