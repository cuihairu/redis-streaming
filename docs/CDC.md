# CDC

Module: `cdc/`

变更数据捕获（Change Data Capture）。把数据库变更转成统一的 `ChangeEvent`，供下游 MQ/聚合/存储等模块消费。主源码 18 个文件：根包 `io.github.cuihairu.redis.streaming.cdc` 10 个、`cdc.impl` 7 个、`cdc.mq` 1 个。

## 模块职责
- 3 种 connector：MySQL binlog、PostgreSQL 逻辑复制（test_decoding 插件）、JDBC 轮询；统一由 `CDCConnectorFactory` 按 `ConnectorType` 创建。
- 统一事件模型 `ChangeEvent`；配置由 `CDCConfigurationBuilder` 构建为 `CDCConfiguration`。
- 连接器生命周期、健康状态、指标由 `CDCManager` 协调（`CDCHealthStatus` / `CDCMetrics`）。
- 桥接：`CDCSource` 把连接器接入 core `StreamSource`；`cdc.mq.ChangeEventQueueSink` 接入 core `StreamSink` 写 MQ topic。

## 对外接口

### CDCConnector（连接器契约）

```java
CompletableFuture<Void> start();
CompletableFuture<Void> stop();
List<ChangeEvent> poll();
void commit(String position);
boolean isRunning();
String getName();
CDCConfiguration getConfiguration();
void setEventListener(CDCEventListener listener);
CDCHealthStatus getHealthStatus();
CDCMetrics getMetrics();
String getCurrentPosition();
void resetToPosition(String position);
```

公共基类 `cdc.impl.AbstractCDCConnector`（三个连接器的父类）：
- `start()` / `stop()` 在异步任务里执行；子类实现模板方法 `doStart` / `doStop` / `doPoll` / `doCommit` / `doResetToPosition`。
- 连接器未启动时 `poll()` 返回空列表；轮询异常不向调用方抛出——`errorsCount` 计数、健康降级 `DEGRADED`、回调 `onConnectorError`，然后返回空列表。
- 启动失败与停止路径都会关闭后台调度器与连接池（不泄漏线程）。
- `pollingIntervalMs > 0` 时启动单线程后台调度排空批次（语义见下文「Polling 语义」）。

### CDCConnectorFactory

```java
enum ConnectorType { MYSQL_BINLOG, POSTGRESQL_LOGICAL_REPLICATION, DATABASE_POLLING }

static CDCConnector create(ConnectorType type, CDCConfiguration configuration);
static CDCConnector create(String typeName, CDCConfiguration configuration); // 大小写不敏感；未知类型抛 IllegalArgumentException
static CDCConnector createMySQLBinlog(CDCConfiguration configuration);
static CDCConnector createPostgreSQLLogicalReplication(CDCConfiguration configuration);
static CDCConnector createDatabasePolling(CDCConfiguration configuration);
```

### CDCConfiguration / CDCConfigurationBuilder

`CDCConfiguration` 接口方法：`getName()`、`getType()`、`getProperties()`、`getProperty(key)`、`getProperty(key, defaultValue)`、`getDatabaseUrl()`、`getUsername()`、`getPassword()`、`getTableIncludes()`、`getTableExcludes()`、`getPollingIntervalMs()`、`getBatchSize()`、`isAutoStart()`、`isSnapshotEnabled()`、`getSnapshotMode()`、`validate()`（名称为空抛 `IllegalArgumentException`）。

`CDCConfigurationBuilder`：
- 静态工厂 `forMySQLBinlog(name)` / `forPostgreSQLLogicalReplication(name)` / `forDatabasePolling(name)`（三者都只是 `name(...)`，类型相关配置统一走 `property(...)`）。
- 基础方法：`name(String)`（必填，`build()` 时空名抛异常）、`username(String)`、`password(String)`、`batchSize(int)`、`pollingIntervalMs(long)`、`property(String, Object)`、`properties(Map)`、`build()`。
- 针对性快捷方法（均是 `property` 的别名，见下文配置表）：`mysqlHostname/mysqlPort/mysqlServerId/mysqlBinlogFilename/mysqlBinlogPosition`、`postgresqlHostname/postgresqlPort/postgresqlDatabase/postgresqlSlotName/postgresqlPublicationName/postgresqlStatusInterval`、`jdbcUrl/driverClass/tables/timestampColumn/incrementalColumn/queryTimeout`。

### CDCManager

```java
void addConnector(CDCConnector connector);        // 重名抛 IllegalArgumentException
CDCConnector removeConnector(String name);        // 移除并先 stop()（10 秒超时），未找到返回 null
CDCConnector getConnector(String name);
List<CDCConnector> getAllConnectors();
CompletableFuture<Void> start();                  // 并行启动全部连接器，成功后开启健康巡检
CompletableFuture<Void> stop();                   // 并行停止；即使个别连接器停止失败也会关停巡检线程池
Map<String, List<ChangeEvent>> pollAll();
void commitAll(Map<String, String> positions);
Map<String, CDCHealthStatus> getHealthStatusAll();
Map<String, CDCMetrics> getMetricsAll();
Map<String, String> getCurrentPositionsAll();     // 尚未推进过位置的连接器会被省略
boolean isRunning();
int getConnectorCount();
int getRunningConnectorCount();
```

健康巡检：`start()` 成功后以 30 秒间隔、4 线程池调度 `monitorConnectorHealth()`，对 `UNHEALTHY` 连接器打 warn 日志。

### CDCEventListener（全部为 default 空实现）

`onConnectorStarted(name)`、`onConnectorStopped(name)`、`onEventsCapture(name, eventCount)`、`onEvents(name, events)`（后台调度排空批次后的交付点，events 非空）、`onConnectorError(name, error)`、`onHealthStatusChanged(name, oldStatus, newStatus)`、`onPositionCommitted(name, position)`、`onSnapshotStarted(name, tableCount)`、`onSnapshotCompleted(name, recordCount)`。

### CDCHealthStatus / CDCMetrics

- `CDCHealthStatus`：`Status { HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN }`；字段 `status/message/timestamp/eventsCaptured/errorsCount/currentPosition`；静态工厂 `healthy/degraded/unhealthy/unknown(String)`；`isHealthy()` / `isUnhealthy()`。
- `CDCMetrics`：字段 `totalEventsCaptured/insertEvents/updateEvents/deleteEvents/schemaChangeEvents/snapshotRecords/errorsCount/averageEventLatencyMs/lastEventTime/lastCommitTime/startTime/currentPosition`；派生方法 `getDataChangeEvents()`、`getEventRate()`（事件数 / 运行秒数）；不可变更新器 `withEventCounts/withPosition/withCommit/withError/withSnapshot`。

### ChangeEvent

字段：`eventType`、`database`、`table`、`key`、`beforeData`、`afterData`、`timestamp`、`transactionId`、`position`、`metadata`、`source`。
辅助方法：`isDataChange()`、`isSchemaChange()`、`isTransactionEvent()`、`getFullTableName()`。

`EventType` 枚举定义 7 个值，**现有三个连接器实际只产出 `INSERT` / `UPDATE` / `DELETE`**；`SCHEMA_CHANGE` / `TRANSACTION_BEGIN` / `TRANSACTION_COMMIT` / `HEARTBEAT` 仅存在于枚举与 `isSchemaChange()`/`isTransactionEvent()` 判断中，未见于连接器实现。

### 三个连接器（公共方法与位置格式）

| 连接器 | 依赖/机制 | `position` 格式 | 连接器自身额外公共方法 |
|---|---|---|---|
| `cdc.impl.MySQLBinlogCDCConnector` | `mysql-binlog-connector-java` 的 `BinaryLogClient` | `binlog文件:偏移`（如 `mysql-bin.000003:4261`） | `getBinlogFilename()`、`getBinlogPosition()`、`isConnected()` |
| `cdc.impl.PostgreSQLLogicalReplicationCDCConnector` | PG JDBC 逻辑复制 + `test_decoding` 插件 | LSN 字符串（`LogSequenceNumber.asString()`） | `getCurrentLSN()`、`getSlotName()`、`getPublicationName()`、`isStreamActive()` |
| `cdc.impl.DatabasePollingCDCConnector` | JDBC + HikariCP 按水位列增量扫描 | `表名:水位值`（冒号后可能含冒号，提交时按 `split(":", 2)` 解析） | `getTables()`、`getTimestampColumn()`、`getIncrementalColumn()`、`getLastPolledValues()`、`isDataSourceAvailable()` |

支撑类（包私有/内部，不在对外 API 面）：`cdc.impl.TableFilter`（`table.includes`/`table.excludes` 的 `*` 通配匹配，命中 `table` 或 `schema.table` 全名）、`cdc.impl.MySQLColumnNameResolver`（binlog 行的列名解析）、`cdc.impl.BackpressureSettings`（背压配置解析）。

### CDCSource / cdc.mq.ChangeEventQueueSink

- `CDCSource implements StreamSource<ChangeEvent>`：`CDCSource(CDCConnector)` 等价于 `(connector, 100L, 3)`；`CDCSource(CDCConnector, long pollIntervalMs, int maxIdlePolls)`（`pollIntervalMs >= 0`、`maxIdlePolls >= 1`，否则抛 `IllegalArgumentException`）。`run(ctx)` 未启动时会先 `start().join()`，连续 `maxIdlePolls` 次空 poll 即返回；`cancel()` 调 `connector.stop()`。构造时若连接器配置的 `pollingIntervalMs > 0` 会打 warn 提示拉取方与调度器抢批次。
- `cdc.mq.ChangeEventQueueSink implements StreamSink<ChangeEvent>`：`ChangeEventQueueSink(MessageProducer, String topic)`（等价超时 10 秒）或 `(MessageProducer, String topic, long sendTimeoutSeconds)`（必须 > 0）。`invoke(event)` 忽略 null，把事件转成自描述 payload（键：`eventType`、`database`、`table`、`key`、`before`、`after`、`timestamp`），以 `event.getKey()` 作 MQ 分区键，同步 `producer.send(...).get(timeout, SECONDS)` 等待，失败上抛交由运行时重试/DLQ。

## 配置项

### Builder 基础参数

| 方法 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `name` | String | 必填 | 连接器名，`build()` 时空名抛 `IllegalArgumentException` |
| `username` / `password` | String | 无 | 数据库账号密码 |
| `batchSize` | int | `100` | 单次 `poll()` 从事件队列最多取出的事件数 |
| `pollingIntervalMs` | long | `1000` | 后台调度排空间隔；`0` = 关闭调度（纯拉取） |

### 通用属性键（`property(key, value)`）

| 键 | 类型 | 默认值 | 消费者与行为 |
|---|---|---|---|
| `table.includes` | `List<String>` | 空（全部放行） | 三个连接器建 `TableFilter`；`*` 通配，匹配 `schema.table` 或 `table` |
| `table.excludes` | `List<String>` | 空 | 同上，命中即排除 |
| `snapshot.enabled` | boolean | `false` | 仅轮询连接器：是否把存量行作为 INSERT 发出 |
| `snapshot.mode` | String | `initial` | 仅轮询连接器：`initial`/`when_needed` 捕获存量；`never` 不捕获（即使 `snapshot.enabled=true`） |
| `event.queue.capacity` | int | `10000` | 三个连接器的有界事件队列容量；缺失/非数字/非正数回退默认并打 warn |
| `auto.start` | boolean | `false` | `CDCConfiguration.isAutoStart()` 读取；cdc 模块内无消费者（未见于实现） |
| `type` | String | `unknown` | `getType()` 读取；模块内不消费 |
| `database.url` | String | 无 | `getDatabaseUrl()` 读取；模块内不消费（轮询连接器用的是 `jdbc.url`） |

### MySQL binlog 专属

| 键 | 类型 | 默认值 |
|---|---|---|
| `hostname` | String | `localhost` |
| `port` | int | `3306` |
| `server.id` | long | `1` |
| `binlog.filename` | String | 未设置（连接器不调 `setBinlogFilename`，起点由 mysql-binlog-connector-java 的默认行为决定） |
| `binlog.position` | long | `0`（仅在设置了 `binlog.filename` 时应用） |
| `connect.timeout.ms` | int | `10000`（`connect(timeout)` 握手超时） |
| `reconnect.backoff.initial.ms` | long | `1000`（重连首次退避，之后翻倍） |
| `reconnect.backoff.max.ms` | long | `30000`（取 `max(initial, 配置值)` 为上限） |
| `schema.resolve.columns` | boolean 字符串 | `"true"`；置 `false` 关闭列名解析（事件列名退化为 `col_0`/`col_1`…） |
| `schema.jdbc.url` | String | `jdbc:mysql://<hostname>:<port>/information_schema` |
| `schema.query.timeout.seconds` | int | `5` |

### PostgreSQL 逻辑复制专属

| 键 | 类型 | 默认值 |
|---|---|---|
| `hostname` | String | `localhost` |
| `port` | int | `5432` |
| `database` | String | **必填**，缺失抛 `IllegalArgumentException` |
| `slot.name` | String | `cdc_slot`（不存在时 `pg_create_logical_replication_slot(slot, 'test_decoding')`） |
| `publication.name` | String | 无（null 则不创建；设置后不存在时 `CREATE PUBLICATION <name> FOR ALL TABLES`） |
| `status.interval.ms` | long | `10000`（复制流状态回报间隔） |
| `reconnect.backoff.ms` | long | `1000`（`poll()` 驱动的重建流最小间隔） |

### 轮询（DatabasePolling）专属

| 键 | 类型 | 默认值 |
|---|---|---|
| `jdbc.url` | String | **必填**，缺失抛 `IllegalArgumentException` |
| `driver.class` | String | 无（交给 Hikari 自行加载） |
| `tables` | String（逗号分隔） | **必填至少一张表**；再经 `table.includes/excludes` 过滤，过滤后为空同样抛异常 |
| `timestamp.column` | String | `updated_at` |
| `incremental.column` | String | 无（设置了就用水位列，否则用 `timestamp.column`） |
| `query.timeout.seconds` | int | `30` |
| `poll.batch.limit` | int | `1000`（每条扫描语句取 `LIMIT limit+1` 探测截断） |

轮询连接器的固定行为：Hikari 池参数为 `maximumPoolSize=10`、`minimumIdle=2`、`connectionTimeout=30000`、`idleTimeout=600000`、`maxLifetime=1800000`（不可配置）；每轮每表最多 1000 个批次（防水位不推进时空转）；事件类型只能是 `INSERT`（轮询无法区分更新）；行 key 优先取 `id`、其次 `pk`，否则非空值按 `_` 拼接。

## Polling 语义（重要）

### 调度轮询 vs 拉取
- `pollingIntervalMs > 0`（默认 1000）时，后台调度器周期性排空批次：**仅在注册了 `CDCEventListener` 时**才把事件交付到 `onEvents(connectorName, events)`；未注册监听器时调度器不排空，批次留给 `poll()` 拉取——两条路径都不会丢事件。
- 外部拉取消费者请设置 `pollingIntervalMs(0)`，只用 `poll()` 主动拉取，避免与调度器抢批次（`CDCSource` 构造时也会对 `pollingIntervalMs > 0` 打 warn）。

### 初始快照（DatabasePollingCDCConnector）
| 配置 | 行为 |
|------|------|
| `snapshot.enabled=true` + `snapshot.mode` 为 `initial`（默认）或 `when_needed` | 启动时把**已存在的行**作为 INSERT 事件发出（回调 `onSnapshotStarted`/`onSnapshotCompleted`） |
| `snapshot.enabled=true` + `snapshot.mode=never` | 不捕获存量行，基线取 `MAX(incremental/timestamp column)` |
| `snapshot.enabled=false`（默认） | 同 `never`：跳过存量行，从当前最大值开始增量轮询 |

> 未开启快照时存量行被跳过是刻意行为（默认值），如需全量导入历史数据请显式开启 `snapshot.enabled=true`。同一实例 stop→start 会保留并恢复水位，不重新取 MAX。

## 断连与重连（CDC-H3）

- **MySQL binlog**：注册 `BinaryLogClient` 生命周期监听；意外断连时健康转 `UNHEALTHY`、回调 `onConnectorError`，在独立单线程（守护线程 `mysql-binlog-reconnect-<name>`）里按 `reconnect.backoff.initial.ms` 起步、逐次翻倍、上限 `reconnect.backoff.max.ms` 重试 `connect(connectTimeoutMs)`，从当前水位续读（断连窗口重放）。`poll()` 里若发现 client 未连接且无重连循环，也会触发同一条恢复路径。
- **PostgreSQL**：读 WAL 失败时拆掉死流与连接，`poll()` 按 `reconnect.backoff.ms` 限速重建连接+流，从 `lastReceivedLSN` 续读；若 slot 在服务端已丢失或被标记 `lost`（PG14+），则**停止重连**并保持 `UNHEALTHY`（提示重建 slot 后 `resetToPosition` 恢复），避免静默跳过已丢弃的 WAL。
- **轮询**：无远程流可断；连接池不可用时 `isDataSourceAvailable()` 返回 false，错误经 `onConnectorError` 上报。

## 背压与有界队列（CDC-M1）

三个连接器的事件队列都是 `ArrayBlockingQueue`（容量 `event.queue.capacity`，默认 10000）：队列满时生产端以 50ms 分片阻塞等待，而不是无限增长；连接器停止/中断时生产端不再入队——MySQL/PG 以异常打断当前事件处理，使水位**不会越过未投递事件**（重启后从上次提交位置重读，至少一次）。轮询连接器的未入队行停在水位之下，下轮重新扫描。

## Minimal Sample（MySQL binlog；与 `CDCIntegrationExamplesTest` 同构）

```java
import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.CDCConnectorFactory;
import io.github.cuihairu.redis.streaming.cdc.CDCManager;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;

import java.util.List;

CDCConfiguration cfg = CDCConfigurationBuilder.forMySQLBinlog("mysql-binlog")
        .username("root")
        .password("secret")
        .mysqlHostname("127.0.0.1")
        .mysqlPort(3306)
        .mysqlServerId(17401)
        .batchSize(10)
        .pollingIntervalMs(0)                       // 纯拉取：关闭后台调度
        .property("table.includes", java.util.List.of("test_db.orders"))
        .build();

var connector = CDCConnectorFactory.createMySQLBinlog(cfg);
CDCManager manager = new CDCManager();
manager.addConnector(connector);
manager.start().join();

List<ChangeEvent> events = connector.poll();
for (ChangeEvent e : events) {
    // ... handle e ...
    String pos = connector.getCurrentPosition();
    if (pos != null) {
        connector.commit(pos);                      // 推进位点，避免重复消费
    }
}
manager.stop().join();
```

轮询连接器的等价写法（对应 `DatabasePollingH2IntegrationTest`）：

```java
CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("h2-poll")
        .username("sa").password("")
        .batchSize(50)
        .pollingIntervalMs(0)
        .property("jdbc.url", "jdbc:h2:mem:cdc")
        .property("driver.class", "org.h2.Driver")
        .property("tables", "audit")
        .property("timestamp.column", "updated_at")
        .property("snapshot.enabled", true)         // 需要存量行时显式打开
        .build();
var connector = CDCConnectorFactory.createDatabasePolling(cfg);
```

## 接入流式 API 的桥接

```java
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.cdc.CDCSource;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import io.github.cuihairu.redis.streaming.cdc.mq.ChangeEventQueueSink;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;

// 1) CDC 连接器 -> StreamSource(有界排空:连续 maxIdlePolls 次空 poll 即返回)
CDCSource source = new CDCSource(connector);                 // (connector, 100ms, 3)
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
DataStream<ChangeEvent> stream = env.addSource(source);      // 内存引擎:addSource 立即执行 run() 并缓存记录
stream.map(ChangeEvent::getAfterData).print();

// 2) ChangeEvent -> MQ topic(自描述 payload,key 作分区键,发送失败上抛供重试/DLQ)
stream.addSink(new ChangeEventQueueSink(producer, "cdc-orders"));   // 默认 10s 发送超时
```

- `cdc.CDCSource`：把任意 `CDCConnector` 接入 core `StreamSource`；`run()` 会先启动未运行的连接器，`cancel()` 调 `connector.stop()`。
- `cdc.mq.ChangeEventQueueSink`：实现 core `StreamSink<ChangeEvent>`（cdc 模块对 mq 是 `implementation` 依赖，见 `cdc/build.gradle`）。
- 连接器本体（MySQL binlog / PG 逻辑复制 / JDBC 轮询）与 `CDCManager` 不变；本模块不直接实现 Redis Stream 消费——消费侧用 `ChangeEventQueueSink` 写入 MQ 后，由 runtime `RedisStreamExecutionEnvironment.fromMqTopic(topic, consumerGroup)` 承接。

## References
- Design.md · [source-sink.md](source-sink.md) · [MQ.md](MQ.md)
