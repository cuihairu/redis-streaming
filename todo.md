# TODO（redis-streaming）

基于当前仓库代码/文档的“未完成点”梳理（以可直接落地的任务为主）。

生成时间：2025-12-12

## P0（阻塞项）

### 1) `./gradlew test` 会间接跑到 `integrationTest`（未启动 Redis 时大量失败）
**状态**：已修复（mq/registry 的 JaCoCo 仅绑定单元测试 `test`）

- 现象：执行 `./gradlew test` 时，`mq` / `registry` 的 `integrationTest` 会被拉起；如果本机未启动 Redis（默认 `127.0.0.1:6379`），测试会大量失败。
- 根因：`mq`/`registry` 模块把所有 `Test`（包含 `integrationTest`）都纳入 `jacocoTestReport` 的依赖，并且所有 `Test` 结束后都会 `finalizedBy jacocoTestReport`。
  - `mq/build.gradle`：`jacocoTestReport.dependsOn tasks.withType(Test)` + `tasks.withType(Test).finalizedBy(jacocoTestReport)`
  - `registry/build.gradle`：同上
- 目标行为：
  - `./gradlew test`：只跑单元测试（`@Tag("integration")` 必须被排除），不要求 Redis
  - `./gradlew integrationTest` / `./gradlew check`：才需要 Redis
  - 覆盖率报告：建议挂到 `check` 或单独任务，而不是 `test` 的 finalizedBy（避免误触发集成测试）

### 2) `runtime` 模块目前是占位符（核心执行引擎未实现）
**状态**：已部分修复（新增最小可用 In-Memory Runtime）

- 已实现：
  - `StreamExecutionEnvironment`：`fromElements/fromCollection/addSource`
  - 基于 iterator 的 `DataStream`/`KeyedStream`（`map/filter/flatMap/keyBy/addSink/print`；`process/reduce/getState`）
- 已补齐（in-memory 语义）：`window(...)` / `sum(...)`
  - 说明：当前 window 以“消费完整上游迭代器后一次性产出结果”的 batch 风格实现；未实现 trigger/timer/watermark 协调。
- 仍未实现：watermark/timer/Checkpoint 等；生产级 runtime 仍在规划中（详见 `runtime/README.md`）。

## P1（重要但不阻塞）

### 3) `table` 模块存在未实现 API
**状态**：已修复（补齐 `toStream()` / `groupBy()` 等最小可用能力）

- 已实现：
  - `InMemoryKTable.toStream()` / `RedisKTable.toStream()`：以 snapshot 方式导出为 runtime 的 `DataStream`
  - `RedisKTable.groupBy(...)`：提供 `RedisKGroupedTable` 聚合能力
  - `StreamTableConverter`：支持 `DataStream -> KTable`（In-Memory）

### 4) CEP：高级量词/严格相邻等已知问题待修复
**状态**：已修复

- `cep/CEP_ISSUES.md` 列出尚未正确工作的能力：
  - Optional `?`
  - Strict contiguity `next`
  - Range quantifiers `{n,m}`
- 文档与测试已同步到当前包路径（`io/github/cuihairu/redis/streaming/...`）。

### 5) 文档/示例中的 Java 包名仍混用 `redis-streaming`（带连字符，无法作为 Java package）
**状态**：已修复（示例/命令/import 已统一为 `io.github.cuihairu.redis.streaming...`）

当前代码包名为：`io.github.cuihairu.redis.streaming...`（无连字符）。

已修复的典型位置（不止这些）：
- `RUNNING_EXAMPLES.md`：`mainClass` 包名已改为 `io.github.cuihairu.redis.streaming...`
- `README.md`：示例 import 包名已改为 `io.github.cuihairu.redis.streaming...`
- `QUICK_START.md`：示例 import 包名已改为 `io.github.cuihairu.redis.streaming...`
- `CLAUDE.md`：package structure/命令示例已改为 `io.github.cuihairu.redis.streaming...`

### 6) `examples` 中存在 `.broken` 示例文件

**状态**：已修复（仓库已无 `.broken` 示例文件；相关文档已同步）

### 7) Wiki/文档中存在 TBD/过时段落

**状态**：已部分修复（关键 Wiki 页面已补齐最小可用说明，移除 TBD 占位）

典型位置：
- `wiki/Runtime.md`（TBD）
- `wiki/CDC.md`（TBD）
- `wiki/Checkpoint.md`（TBD）
- `wiki/Table.md`（TBD）
- `wiki/State.md`（TBD，且当前代码里已存在 `state.backend.StateBackend`，文档需更新）
- `wiki/Source.md`（TBD）

建议：继续补齐“能跑的最小示例/配置”，并把限制/未实现点写清楚（避免误导）。

## P2（清理/演进）

### 8) 大量 `*.backup` 源码/测试残留需要定策略

**状态**：已清理（移除 `src/main/java.backup`、`src/test.backup` 等备份目录，并在 `.gitignore` 中添加忽略规则）

### 9) Checkpoint 协调器里有未使用的超时配置

**状态**：已修复（支持 pending checkpoint 超时清理，并在 ack/complete 时检查超时）

- 当前实现会在 `triggerCheckpoint/acknowledgeCheckpoint/completeCheckpoint` 入口执行超时清理，并对超时 checkpoint 打日志告警。
- 进一步演进（可选）：Checkpoint API 若增加 “failed/aborted” 状态，可在超时时落盘状态以便可观测与治理。

### 10) 约定/指南与实际工程状态需要再对齐一次

**状态**：已部分修复（补齐 `docs/` 目录以修复链接；并对齐 AGENTS/CLAUDE/wiki 的占位文档）

- `AGENTS.md` / `CLAUDE.md` / `README.md` / `settings.gradle` 的模块列表、命名规范、运行命令存在不一致处
- 需要统一“对外接口 + 使用文档 + 工程结构”，降低新用户上手成本
