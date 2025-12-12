# 潜在 Bug 分析（redis-streaming）

说明：
- 这是对当前代码实现做的静态分析 + 少量本地验证（我已验证 `./gradlew test -x :mq:integrationTest -x :registry:integrationTest` 可以通过）。
- “潜在”表示：有些点是确定会导致错误/数据丢失，有些是高概率风险/边界条件问题；均给出代码定位，方便你逐个确认与修复。

生成时间：2025-12-12

## P0（高风险 / 影响正确性）

### 1) `./gradlew test` 可能会被间接触发 `integrationTest`，导致未启动 Redis 时大量失败
**状态**：已修复（mq/registry 的 JaCoCo 仅绑定单元测试 `test`）

- **影响**：CI/本地执行单元测试时，如果机器未启动 Redis（默认 `127.0.0.1:6379`），会出现大量“Connection refused”失败；并且表现为“跑 test 却跑了 integrationTest”。
- **定位**：
  - `mq/build.gradle:36`：`jacocoTestReport` 依赖 `tasks.withType(Test)`（会把 `integrationTest` 也纳入）
  - `mq/build.gradle:48`：所有 `Test` 结束后 `finalizedBy(jacocoTestReport)`（可能反向拉起其他 Test 任务）
  - `registry/build.gradle:36`、`registry/build.gradle:48`：同样问题
- **建议修复方向**：让 `jacocoTestReport` 仅依赖 `test`（单元测试），或者将覆盖率报告绑定到 `check`，不要从 `test` finalizedBy 去触发。

### 2) MQ 的 DLQ “大 Payload 存 Hash” 与 DLQ 解析/重放逻辑不匹配，可能导致 DLQ 消费/重放时 payload 丢失
**状态**：已修复（支持解析 DLQ 时按 hashRef 加载；replayHandler 场景也会解引用）

- **现象**：当 payload 过大，`buildDlqEntry` 会把 payload 存到 Redis bucket，并在 headers 里写入 hashRef，然后将 DLQ entry 的 `payload` 字段置为 `null`；但 DLQ 解析与重放逻辑并不会把 payload 从 hashRef 加载回来。
- **影响**：
  - DLQ 消费者拿到的 `Message.payload` 可能为 `null`（实际 payload 在 Redis bucket 里），业务处理/排障会直接缺数据；
  - DLQ 重放回 topic 时可能继续带着 `payload=null`，造成不可恢复的数据丢失。
- **定位**：
  - `mq/src/main/java/io/github/cuihairu/redis/streaming/mq/impl/StreamEntryCodec.java:174`：DLQ 大 payload 存储（payload 置 `null`）
  - `mq/src/main/java/io/github/cuihairu/redis/streaming/mq/impl/StreamEntryCodec.java:203`：`parseDlqEntry` 只 `m.setPayload(data.get("payload"))`，并未按 hashRef 反查加载
  - `mq/src/main/java/io/github/cuihairu/redis/streaming/mq/impl/StreamEntryCodec.java:233`：`buildPartitionEntryFromDlq` 直接复制 DLQ 的 `payload` 字段（如果是 `null` 就一直是 `null`），也没有迁移/解引用 hashRef
- **额外关联风险**：`reliability` 模块也有一份 `DeadLetterCodec`，同样只复制 `payload`，不处理 hashRef（`reliability/src/main/java/.../DeadLetterCodec.java:28`）。

### 3) CDC 的“表 include/exclude”配置在实现中未被使用（配置形同虚设）
**状态**：已修复（MySQL/PostgreSQL/Polling 均已应用 include/exclude 过滤）

- **影响**：用户设置 `getTableIncludes()/getTableExcludes()` 期望过滤表，但当前实现不会过滤；在生产环境可能导致采集范围失控（性能/安全/成本问题）。
- **定位**：
  - `cdc/src/main/java/io/github/cuihairu/redis/streaming/cdc/CDCConfiguration.java:75`、`cdc/src/main/java/io/github/cuihairu/redis/streaming/cdc/CDCConfiguration.java:82`：接口定义了 include/exclude
  - 目前 `cdc/src/main/java/io/github/cuihairu/redis/streaming/cdc/impl/*` 未发现对 `getTableIncludes/getTableExcludes` 的使用（静态搜索结果为空）。

### 4) MySQL Binlog CDC 的 position 跟踪不正确（currentPosition 基本不会随事件推进）
**状态**：position 推进已修复（使用 header nextPosition）；列名映射仍为简化实现

- **影响**：commit/恢复位点不可用或严重不准；重启后可能重复消费或跳过。
- **定位**：
  - `cdc/src/main/java/io/github/cuihairu/redis/streaming/cdc/impl/MySQLBinlogCDCConnector.java:272`：`updateCurrentPosition(Event event)` 读取了 `EventHeader` 但没有使用；仅用 `binlogFilename + ":" + binlogPosition.get()`
  - `cdc/src/main/java/io/github/cuihairu/redis/streaming/cdc/impl/MySQLBinlogCDCConnector.java:266`：`binlogPosition` 只在 ROTATE 事件里更新
- **附带实现不完整**：
  - `cdc/src/main/java/io/github/cuihairu/redis/streaming/cdc/impl/MySQLBinlogCDCConnector.java:278`：列名被写死为 `col_i`（注释也承认“真实实现应使用列名”），导致下游无法按字段名消费。

### 5) PostgreSQL 逻辑复制读取 ByteBuffer 的方式可能错误，导致解析出脏数据/截断数据
**状态**：已修复（读取 buffer.remaining()）

- **影响**：`test_decoding` 消息解析可能混入多余字节（例如 buffer backing array 未使用区），从而导致 regex 解析失败或产生错误事件。
- **定位**：
  - `cdc/src/main/java/io/github/cuihairu/redis/streaming/cdc/impl/PostgreSQLLogicalReplicationCDCConnector.java:212`
  - `cdc/src/main/java/io/github/cuihairu/redis/streaming/cdc/impl/PostgreSQLLogicalReplicationCDCConnector.java:217`：使用 `arrayOffset()` 和 `source.length - offset`，但未考虑 `buffer.position()/limit()`，高概率应使用 `buffer.remaining()`

## P1（中风险 / 运行时问题）

### 6) `KafkaSource` / `KafkaSink` 使用了 `compileOnly kafka-clients`，直接使用相关类时可能在运行时 `ClassNotFoundException`
**状态**：已修复（kafka-clients 改为运行时可用依赖）

- **影响**：库消费者如果只引入 `:source`/`:sink` 模块（默认依赖不会携带 kafka-clients），但实际 new 了 `KafkaSource`/`KafkaSink`，会在运行时报类缺失。
- **定位**：
  - `source/build.gradle:12`：`compileOnly 'org.apache.kafka:kafka-clients:3.6.1'`
  - `sink/build.gradle:12`：`compileOnly 'org.apache.kafka:kafka-clients:3.6.1'`
  - `source/src/main/java/.../KafkaSource.java:7`：直接 import Kafka classes（无反射隔离）
  - `sink/src/main/java/.../KafkaSink.java:7`：同上
- **建议**：要么拆出独立 `kafka` 子模块并用 `implementation/api`，要么用反射隔离 + 文档声明“需要额外依赖”。

### 7) `KafkaSource.seekToBeginning/seekToEnd` 可能在未分配 partition 时无效
**状态**：已修复（seek 前 best-effort 触发一次 poll 以确保 assignment）

- **影响**：`consumer.assignment()` 在第一次 poll 前可能为空；调用 seek 不生效或抛异常（取决于 KafkaConsumer 状态）。
- **定位**：`source/src/main/java/io/github/cuihairu/redis/streaming/source/kafka/KafkaSource.java:197`

### 8) MQ ack 过程吞异常，可能造成“看起来成功但实际上未 ack”的隐性故障
**状态**：已修复（ack 失败会告警并抛出，让消息保持 pending 并可重试）

- **影响**：ack 失败会被静默吞掉，外层逻辑可能继续推进（例如更新内部状态/提交 frontier 等），最终导致重复消费、pending 堆积或难以定位的消息一致性问题。
- **定位**：
  - `mq/src/main/java/io/github/cuihairu/redis/streaming/mq/impl/RedisMessageConsumer.java:650`：ack 主逻辑包裹在 `try { ... } catch (Exception ignore) {}`
  - 同方法内 `commit frontier` 更新也吞异常：`mq/src/main/java/.../RedisMessageConsumer.java:669`

### 9) `RedisKTable` 生成新表时用 `Object.class` 作为 valueClass，可能导致反序列化类型不符合预期
**状态**：已缓解/基本修复（map/join 结果表会从首个非空结果值推断 valueClass）

- **影响**：`mapValues/join/leftJoin` 返回的 `KTable<K, VR>`，但内部 `valueClass` 被固定为 `Object.class`；读取时 Jackson 会反序列化为 `LinkedHashMap`/`ArrayList` 等通用结构，调用方按 `VR` 使用可能触发 `ClassCastException` 或逻辑错误。
- **定位**：
  - `table/src/main/java/io/github/cuihairu/redis/streaming/table/impl/RedisKTable.java:153`
  - `table/src/main/java/io/github/cuihairu/redis/streaming/table/impl/RedisKTable.java:175`
  - `table/src/main/java/io/github/cuihairu/redis/streaming/table/impl/RedisKTable.java:224`
  - `table/src/main/java/io/github/cuihairu/redis/streaming/table/impl/RedisKTable.java:254`

## P2（低风险 / 质量与可维护性）

### 10) 大量“吞异常/忽略异常”的模式会掩盖真实故障
**状态**：部分修复（关键链路：Broker/MQ ack 已不再吞异常；其他 best-effort 场景仍保留但逐步加日志）

- **影响**：问题被隐藏、状态不一致、故障难以排查；尤其是在“写 Redis + 更新本地状态/指标”的链路中更危险。
- **代表性定位**（不完全列举）：
  - `mq/src/main/java/io/github/cuihairu/redis/streaming/mq/impl/RedisMessageConsumer.java:652`
  - `mq/src/main/java/io/github/cuihairu/redis/streaming/mq/broker/impl/DefaultBroker.java:62`（以及文件内多处）
  - `spring-boot-starter/src/main/java/.../StreamRetentionHousekeeper.java:73`（以及文件内多处）

### 11) 文档/示例中的包名混用 `redis-streaming`（连字符）可能导致用户复制代码后无法编译
**状态**：已修复（示例/命令/import 已统一为 `io.github.cuihairu.redis.streaming...`）

- **影响**：用户按文档复制 import/mainClass 直接编译失败，属于“产品级 bug”（降低可用性与可信度）。
- **定位（示例）**：
  - `RUNNING_EXAMPLES.md:14`
  - `README.md:505`
