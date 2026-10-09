# 控制面设计（Job Submit / Upgrade / Rollback + 权限与审计）

本文描述 `runtime` 控制面（P4）的范围、约束与分阶段落地策略。控制面要解决的问题是：**作业如何被声明、存储、升级、回滚，以及谁有权做这些操作、做了什么**——而作业本身仍由既有执行引擎（`RedisStreamExecutionEnvironment` + `RedisJobClient`）运行。

## 现状（当前实现能力）

- 作业在应用进程内构建与启动：`RedisStreamExecutionEnvironment.executeAsync()` 返回 `RedisJobClient`（进程内句柄：`cancel/pause/resume/scaleParallelism/triggerCheckpointNow/diagnostics`）。
- 作业的"重建信息"（pipeline 怎么搭、配置是什么）只存在于启动进程的代码里；Redis 中有状态与位点（checkpoint/offset 按 `job/topic/group/partition` 键控），但没有**作业规格（spec）的存储**。
- 已具备的相邻能力（控制面直接复用）：
  - `scaleParallelism(n)`：运行期改并行度（2026-10-10）。
  - checkpoint 向前兼容：跨并行度恢复已验证（2026-10-10）。
  - `RedisLeaderElector`：租约选举 + fencing token（2026-10-01）。
  - key 前缀约定：`streaming:runtime:*`（checkpoint/sinkDedup 等）。

## 约束与难点

1. **Pipeline 不可序列化**：DataStream 图由 lambda 组成，无法存进 Redis。因此作业规格必须是**声明式**的：`(pipeline 工厂名, 配置 Map)`；工厂在执行侧本地注册（与 CDC 连接器类型注册同型）。
2. **生命周期归属跨进程**：`RedisJobClient` 是 JVM 本地句柄，控制面无法直接操作别的进程里的作业。执行侧必须有一个**常驻代理**负责"期望状态 → 本地实际状态"的对账（reconcile），控制面只写期望状态。
3. **升级窗口语义**：upgrade = 停旧起新，窗口内积压不丢（consumer group 与 offset 持久，at-least-once），keyed state 经 checkpoint 跨并行度迁移（已验证）。
4. **并发改写同一 spec**：两个操作者同时 upgrade 会互相覆盖——spec 版本号 CAS（Lua compare-and-set），失败方拒绝。
5. **权限**：框架不发明认证体系（与 `LoadBalancer` 同型思路），提供**可插拔授权器**接口 + 全量审计；审计先于授权判定记录。

## 可选方案

### 方案 A：声明式 spec + 期望状态对账（推荐 v1）

控制面 = spec 存储 + 历史 + 审计 + 授权钩子；执行侧 `JobAgent`（应用内、opt-in）轮询/订阅期望状态并在本地 reconcile（launch/stop/upgrade）。这是 K8s controller 模型的最小化移植：**不需要新进程**，多实例各管各的本地作业，跨实例协调只发生在 spec 存储上。

### 方案 B：框架托管 executor 进程

控制面直接把作业拉起到独立 worker 进程。隔离最好，但引入进程管理与打包分发问题，放到 v2 以后。

### 方案 C：纯 spec/配置存储（不执行）

方案 A 的子集，作为 v1.0 的第一个增量先落地。

## V1 落地（方案 A）

### 数据模型与 Redis 布局

前缀 `streaming:runtime:control:`（可配，沿用 runtime 前缀约定）：

| key | 类型 | 内容 |
| --- | --- | --- |
| `jobs` | Hash | jobName → JobSpec JSON |
| `history:<job>` | List | 历史 spec 版本（回滚源，容量上限 N） |
| `status:<job>` | Hash | state（`DESIRED_STOPPED/PENDING_DEPLOY/RUNNING/FAILED`）、instanceId、错误信息、updatedAt |
| `audit` | Stream | `{ts, actor, op, job, fromVersion, toVersion, allowed, detail}`，按长度 XTRIM |

`JobSpec` 字段：`jobName`、`pipelineFactory`（工厂名）、`config`（`Map<String,String>`）、`parallelism`、`version`（单调递增，CAS 依据）、`description`、`updatedBy`、`updatedAt`、`specHash`（内容哈希，对账比较用）。

### API（v1.0：存储与客户端）

`JobControlPlane`：

- `submit(JobSpec)`：新增（重名即拒绝）→ 写 `jobs` + `status=PENDING_DEPLOY` + 审计。
- `get/list`：读 spec / 全量列举。
- `upgrade(jobName, mutator)`：CAS 版本号 → 旧版本入 `history` → 更新 `jobs` → 审计。
- `rollback(jobName)`：取 `history` 末元素作为新期望版本（同样走 CAS + 入审计；版本号仍递增，历史保留原样）。
- `stop(jobName)` / `resume(jobName)`：翻期望状态位。
- `reportStatus(job, state, instanceId, detail)`：仅供 `JobAgent` 调用。
- `tailAudit(limit)`：审计查询。

授权与审计：每个变更方法先写审计（actor/op/结果），再调 `ControlPlaneAuthorizer.authorize(actor, op, job)`；拒绝抛 `ControlPlaneAccessDeniedException`。默认授权器全放行。

### 执行侧（v1.1：JobAgent 对账）

应用内 opt-in 组件，对账循环：

1. 拉取全部 spec + 本地 `status`，与本地已启动作业对账。
2. `PENDING_DEPLOY` 且未被认领 → `SET NX` 认领（instanceId + TTL，租约语义与 MQ lease 同型）→ 本地经工厂构建 pipeline → `executeAsync` → `reportStatus(RUNNING)`。
3. 期望版本 ≠ 本地 `specHash` → 升级流：`pause` → `triggerCheckpointNow()` → `cancel` → 重建 `executeAsync` → `reportStatus(RUNNING)`；任一步失败 `reportStatus(FAILED)`（旧作业已停、backlog 不丢，重试由对账循环驱动）。仅 `parallelism` 变化时走 `scaleParallelism` 快路径，避免整作业重启。
4. `DESIRED_STOPPED` 且本地持有 → `cancel` → `reportStatus(DESIRED_STOPPED)`。

多实例各对账各的（认领与所有权隔离）；单写者要求更高的场景可叠加 leader 选举只允许 leader agent 认领新作业。

### 权限与审计（v1.2：starter 接入）

- Spring Boot starter：`redis-streaming.control-plane.enabled=true` 自动装配 `JobControlPlane` + `JobAgent`（`autoDeploy=true` 才启动对账）；`authorizer` bean 用户优先。
- actor 来源：`JobControlPlane` 方法参数显式传入（运维工具/UI 负责），缺省取系统属性 `user.name`。

## 非目标（v1）

- 跨进程资源配额与多租户隔离（P4 后续条目）。
- 内置认证体系、secret 管理（仅提供授权钩子与审计）。
- 框架托管 executor 进程（方案 B，v2+）。
- 自动故障转移拉起（实例宕机后作业由人/外部编排决定重启；控制面只暴露 `FAILED/超时未续` 的可观测状态）。

## 与现有配置/运维的关系

- 升级/回滚复用 at-least-once + checkpoint 语义：不引入新的投递保证。
- `triggerCheckpointNow`（`deferAckUntilCheckpoint=true` 时）让升级窗口的重复最小化；未配 checkpoint 的作业升级即纯 at-least-once 重放窗口。
- 审计流建议接入既有监控（Micrometer 计数器可在实现时追加）。

## 分阶段计划

- v1.0：`JobSpec` + Redis 存储 + `JobControlPlane` API（submit/upgrade/rollback/stop/resume/list/audit）+ 授权钩子；单测（mock Redisson）+ 集成测试（真实 Redis）。
- v1.1：`JobAgent` 对账（认领/部署/升级/回滚执行/失败报告）+ 集成测试（升级保留 state、回滚、仅并行度变化走快路径）。
- v1.2：starter 自动装配 + 文档（Spring-Boot-Starter.md / Deployment.md 增补）。
