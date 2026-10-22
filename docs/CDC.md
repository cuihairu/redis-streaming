# CDC

Module: `cdc/`

Change Data Capture. Converts database changes into a unified `ChangeEvent` for downstream consumers (MQ/aggregation/storage modules). 18 main source files: 10 in the root package `io.github.cuihairu.redis.streaming.cdc`, 7 in `cdc.impl`, 1 in `cdc.mq`.

## Module Responsibility
- 3 connectors: MySQL binlog, PostgreSQL logical replication (the test_decoding plugin), and JDBC polling; all created uniformly by `CDCConnectorFactory` per `ConnectorType`.
- The unified event model `ChangeEvent`; configuration is built by `CDCConfigurationBuilder` into `CDCConfiguration`.
- Connector lifecycle, health, and metrics are coordinated by `CDCManager` (`CDCHealthStatus` / `CDCMetrics`).
- Bridges: `CDCSource` wires a connector into core's `StreamSource`; `cdc.mq.ChangeEventQueueSink` implements core's `StreamSink` writing an MQ topic.

## Public Interface

### CDCConnector (the connector contract)

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

The common base `cdc.impl.AbstractCDCConnector` (the three connectors' parent):
- `start()` / `stop()` run inside async tasks; subclasses implement the template methods `doStart` / `doStop` / `doPoll` / `doCommit` / `doResetToPosition`.
- `poll()` returns an empty list while the connector is not started; polling exceptions are never thrown at the caller — `errorsCount` increments, health degrades to `DEGRADED`, `onConnectorError` fires, and an empty list returns.
- Both the start-failure and stop paths shut down the background scheduler and connection pool (no thread leaks).
- When `pollingIntervalMs > 0`, a single-threaded background scheduler starts draining batches (semantics under "Polling semantics" below).

### CDCConnectorFactory

```java
enum ConnectorType { MYSQL_BINLOG, POSTGRESQL_LOGICAL_REPLICATION, DATABASE_POLLING }

static CDCConnector create(ConnectorType type, CDCConfiguration configuration);
static CDCConnector create(String typeName, CDCConfiguration configuration); // case-insensitive; unknown types throw IllegalArgumentException
static CDCConnector createMySQLBinlog(CDCConfiguration configuration);
static CDCConnector createPostgreSQLLogicalReplication(CDCConfiguration configuration);
static CDCConnector createDatabasePolling(CDCConfiguration configuration);
```

### CDCConfiguration / CDCConfigurationBuilder

`CDCConfiguration` interface methods: `getName()`, `getType()`, `getProperties()`, `getProperty(key)`, `getProperty(key, defaultValue)`, `getDatabaseUrl()`, `getUsername()`, `getPassword()`, `getTableIncludes()`, `getTableExcludes()`, `getPollingIntervalMs()`, `getBatchSize()`, `isAutoStart()`, `isSnapshotEnabled()`, `getSnapshotMode()`, `validate()` (an empty name throws `IllegalArgumentException`).

`CDCConfigurationBuilder`:
- Static factories `forMySQLBinlog(name)` / `forPostgreSQLLogicalReplication(name)` / `forDatabasePolling(name)` (all three are just `name(...)`; type-specific configuration goes uniformly through `property(...)`).
- Base methods: `name(String)` (required; `build()` throws on an empty name), `username(String)`, `password(String)`, `batchSize(int)`, `pollingIntervalMs(long)`, `property(String, Object)`, `properties(Map)`, `build()`.
- Targeted shortcuts (all aliases of `property`; see the configuration tables below): `mysqlHostname/mysqlPort/mysqlServerId/mysqlBinlogFilename/mysqlBinlogPosition`, `postgresqlHostname/postgresqlPort/postgresqlDatabase/postgresqlSlotName/postgresqlPublicationName/postgresqlStatusInterval`, `jdbcUrl/driverClass/tables/timestampColumn/incrementalColumn/queryTimeout`.

### CDCManager

```java
void addConnector(CDCConnector connector);        // duplicate names throw IllegalArgumentException
CDCConnector removeConnector(String name);        // removes and stop()s first (10s timeout); null when not found
CDCConnector getConnector(String name);
List<CDCConnector> getAllConnectors();
CompletableFuture<Void> start();                  // starts all connectors in parallel, then opens the health patrol
CompletableFuture<Void> stop();                   // stops in parallel; the patrol pool shuts down even if individual connectors fail to stop
Map<String, List<ChangeEvent>> pollAll();
void commitAll(Map<String, String> positions);
Map<String, CDCHealthStatus> getHealthStatusAll();
Map<String, CDCMetrics> getMetricsAll();
Map<String, String> getCurrentPositionsAll();     // connectors that never advanced a position are omitted
boolean isRunning();
int getConnectorCount();
int getRunningConnectorCount();
```

Health patrol: after `start()` succeeds, `monitorConnectorHealth()` runs on a 30-second interval over a 4-thread pool, WARN-logging `UNHEALTHY` connectors.

### CDCEventListener (all default no-ops)

`onConnectorStarted(name)`, `onConnectorStopped(name)`, `onEventsCapture(name, eventCount)`, `onEvents(name, events)` (the delivery point after the background scheduler drains a batch; events is non-empty), `onConnectorError(name, error)`, `onHealthStatusChanged(name, oldStatus, newStatus)`, `onPositionCommitted(name, position)`, `onSnapshotStarted(name, tableCount)`, `onSnapshotCompleted(name, recordCount)`.

### CDCHealthStatus / CDCMetrics

- `CDCHealthStatus`: `Status { HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN }`; fields `status/message/timestamp/eventsCaptured/errorsCount/currentPosition`; static factories `healthy/degraded/unhealthy/unknown(String)`; `isHealthy()` / `isUnhealthy()`.
- `CDCMetrics`: fields `totalEventsCaptured/insertEvents/updateEvents/deleteEvents/schemaChangeEvents/snapshotRecords/errorsCount/averageEventLatencyMs/lastEventTime/lastCommitTime/startTime/currentPosition`; derived methods `getDataChangeEvents()` and `getEventRate()` (events / running seconds); immutable updaters `withEventCounts/withPosition/withCommit/withError/withSnapshot`.

### ChangeEvent

Fields: `eventType`, `database`, `table`, `key`, `beforeData`, `afterData`, `timestamp`, `transactionId`, `position`, `metadata`, `source`.
Helpers: `isDataChange()`, `isSchemaChange()`, `isTransactionEvent()`, `getFullTableName()`.

The `EventType` enum defines 7 values, but **the three existing connectors only ever produce `INSERT` / `UPDATE` / `DELETE`**; `SCHEMA_CHANGE` / `TRANSACTION_BEGIN` / `TRANSACTION_COMMIT` / `HEARTBEAT` exist only in the enum and the `isSchemaChange()`/`isTransactionEvent()` predicates, not in any connector implementation.

### The three connectors (public methods and position formats)

| Connector | Dependency/mechanism | `position` format | Connector-specific public methods |
|---|---|---|---|
| `cdc.impl.MySQLBinlogCDCConnector` | `mysql-binlog-connector-java`'s `BinaryLogClient` | `binlog file:offset` (e.g. `mysql-bin.000003:4261`) | `getBinlogFilename()`, `getBinlogPosition()`, `isConnected()` |
| `cdc.impl.PostgreSQLLogicalReplicationCDCConnector` | the PG JDBC driver's logical replication + the `test_decoding` plugin | an LSN string (`LogSequenceNumber.asString()`) | `getCurrentLSN()`, `getSlotName()`, `getPublicationName()`, `isStreamActive()` |
| `cdc.impl.DatabasePollingCDCConnector` | JDBC + HikariCP, incremental scans on a watermark column | `table:watermark` (the value after the colon may itself contain colons; commit parses with `split(":", 2)`) | `getTables()`, `getTimestampColumn()`, `getIncrementalColumn()`, `getLastPolledValues()`, `isDataSourceAvailable()` |

Support classes (package-private/inner, outside the public API): `cdc.impl.TableFilter` (`table.includes`/`table.excludes` `*` wildcard matching against `table` or the `schema.table` full name), `cdc.impl.MySQLColumnNameResolver` (binlog row column-name resolution), `cdc.impl.BackpressureSettings` (backpressure/polling configuration parsing).

### CDCSource / cdc.mq.ChangeEventQueueSink

- `CDCSource implements StreamSource<ChangeEvent>`: `CDCSource(CDCConnector)` equals `(connector, 100L, 3)`; `CDCSource(CDCConnector, long pollIntervalMs, int maxIdlePolls)` (`pollIntervalMs >= 0`, `maxIdlePolls >= 1`, otherwise `IllegalArgumentException`). `run(ctx)` starts a stopped connector first (`start().join()`), returns after `maxIdlePolls` consecutive empty polls; `cancel()` calls `connector.stop()`. When the connector's `pollingIntervalMs > 0`, the constructor warns that the puller and the scheduler will contend for batches.
- `cdc.mq.ChangeEventQueueSink implements StreamSink<ChangeEvent>`: `ChangeEventQueueSink(MessageProducer, String topic)` (timeout defaults to 10 seconds) or `(MessageProducer, String topic, long sendTimeoutSeconds)` (must be > 0). `invoke(event)` ignores null, converts the event into a self-describing payload (keys: `eventType`, `database`, `table`, `key`, `before`, `after`, `timestamp`), uses `event.getKey()` as the MQ partition key, waits synchronously on `producer.send(...).get(timeout, SECONDS)`, and rethrows failures for the runtime's retry/DLQ.

## Configuration

### Builder base parameters

| Method | Type | Default | Notes |
|---|---|---|---|
| `name` | String | required | the connector name; `build()` throws `IllegalArgumentException` on an empty name |
| `username` / `password` | String | none | database credentials |
| `batchSize` | int | `100` | the maximum events drained from the event queue per `poll()` |
| `pollingIntervalMs` | long | `1000` | the background scheduler's drain interval; `0` = scheduler off (pure pulling) |

### Common property keys (`property(key, value)`)

| Key | Type | Default | Consumers and behavior |
|---|---|---|---|
| `table.includes` | `List<String>` | empty (all pass) | all three connectors build a `TableFilter`; `*` wildcards match `schema.table` or `table` |
| `table.excludes` | `List<String>` | empty | same, matched entries are excluded |
| `snapshot.enabled` | boolean | `false` | polling connector only: whether existing rows are emitted as INSERTs |
| `snapshot.mode` | String | `initial` | polling connector only: `initial`/`when_needed` capture existing rows; `never` does not (even with `snapshot.enabled=true`) |
| `event.queue.capacity` | int | `10000` | the bounded event-queue capacity of all three connectors; missing/non-numeric/non-positive values fall back to the default with a warning |
| `auto.start` | boolean | `false` | read by `CDCConfiguration.isAutoStart()`; no consumer inside the cdc module (not seen in any implementation) |
| `type` | String | `unknown` | read by `getType()`; not consumed inside the module |
| `database.url` | String | none | read by `getDatabaseUrl()`; not consumed inside the module (the polling connector uses `jdbc.url`) |

### MySQL binlog specifics

| Key | Type | Default |
|---|---|---|
| `hostname` | String | `localhost` |
| `port` | int | `3306` |
| `server.id` | long | `1` |
| `binlog.filename` | String | unset (the connector does not call `setBinlogFilename`; the starting point is mysql-binlog-connector-java's default behavior) |
| `binlog.position` | long | `0` (applied only when `binlog.filename` is set) |
| `connect.timeout.ms` | int | `10000` (the `connect(timeout)` handshake timeout) |
| `reconnect.backoff.initial.ms` | long | `1000` (the first reconnect backoff, doubling afterwards) |
| `reconnect.backoff.max.ms` | long | `30000` (the cap, `max(initial, configured)`) |
| `schema.resolve.columns` | boolean string | `"true"`; `false` disables column-name resolution (event column names degrade to `col_0`/`col_1`…) |
| `schema.jdbc.url` | String | `jdbc:mysql://<hostname>:<port>/information_schema` |
| `schema.query.timeout.seconds` | int | `5` |

### PostgreSQL logical replication specifics

| Key | Type | Default |
|---|---|---|
| `hostname` | String | `localhost` |
| `port` | int | `5432` |
| `database` | String | **required**; missing throws `IllegalArgumentException` |
| `slot.name` | String | `cdc_slot` (created via `pg_create_logical_replication_slot(slot, 'test_decoding')` when absent) |
| `publication.name` | String | none (null creates none; when set and absent, `CREATE PUBLICATION <name> FOR ALL TABLES`) |
| `status.interval.ms` | long | `10000` (the replication-stream status report interval) |
| `reconnect.backoff.ms` | long | `1000` (the minimum interval for `poll()`-driven stream rebuilds) |

### Polling (DatabasePolling) specifics

| Key | Type | Default |
|---|---|---|
| `jdbc.url` | String | **required**; missing throws `IllegalArgumentException` |
| `driver.class` | String | none (Hikari loads it itself) |
| `tables` | String (comma-separated) | **required, at least one table**; filtered further by `table.includes/excludes`, and an empty result throws too |
| `timestamp.column` | String | `updated_at` |
| `incremental.column` | String | none (when set, the watermark column is it; otherwise `timestamp.column`) |
| `query.timeout.seconds` | int | `30` |
| `poll.batch.limit` | int | `1000` (each scan statement takes `LIMIT limit+1` to detect truncation) |

The polling connector's fixed behaviors: the Hikari pool is `maximumPoolSize=10`, `minimumIdle=2`, `connectionTimeout=30000`, `idleTimeout=600000`, `maxLifetime=1800000` (not configurable); at most 1000 batches per table per round (preventing spin when the watermark stalls); the event type is always `INSERT` (polling cannot distinguish updates); the row key prefers `id`, then `pk`, otherwise non-null values joined with `_`.

## Polling Semantics

### Scheduled polling vs pulling
- With `pollingIntervalMs > 0` (default 1000), the background scheduler drains batches periodically, delivering events to `onEvents(connectorName, events)` **only when a `CDCEventListener` is registered**; without a listener the scheduler does not drain and batches remain for `poll()` to pull — neither path loses events.
- External pull consumers should set `pollingIntervalMs(0)` and rely purely on `poll()`, avoiding contention with the scheduler (`CDCSource` also warns at construction when `pollingIntervalMs > 0`).

### The initial snapshot (DatabasePollingCDCConnector)
| Configuration | Behavior |
|------|------|
| `snapshot.enabled=true` + `snapshot.mode` of `initial` (default) or `when_needed` | existing rows are emitted as INSERT events at startup (callbacks `onSnapshotStarted`/`onSnapshotCompleted`) |
| `snapshot.enabled=true` + `snapshot.mode=never` | existing rows are not captured; the baseline is `MAX(incremental/timestamp column)` |
| `snapshot.enabled=false` (default) | same as `never`: skip existing rows and start incremental polling from the current maximum |

> Skipping existing rows when the snapshot is off is deliberate (the default); enable `snapshot.enabled=true` explicitly to import full history. A stop→start of the same instance retains and restores the watermark without re-reading MAX.

## Disconnects and Reconnection (CDC-H3)

- MySQL binlog: a `BinaryLogClient` lifecycle listener is registered; on unexpected disconnect the health turns `UNHEALTHY`, `onConnectorError` fires, and a dedicated single thread (the daemon `mysql-binlog-reconnect-<name>`) retries `connect(connectTimeoutMs)` starting at `reconnect.backoff.initial.ms`, doubling each time up to `reconnect.backoff.max.ms`, resuming from the current position (replaying the disconnect window). If `poll()` finds the client disconnected with no reconnect loop running, it triggers the same recovery path.
- PostgreSQL: when reading the WAL fails, the dead stream and connection are torn down; `poll()` rate-limits the rebuild by `reconnect.backoff.ms` and resumes from `lastReceivedLSN`; if the slot is gone server-side or marked `lost` (PG14+), reconnection stops and the health stays `UNHEALTHY` (advising slot recreation + `resetToPosition`), avoiding silently skipping discarded WAL.
- Polling: no remote stream to lose; when the pool is unavailable `isDataSourceAvailable()` returns false and errors surface via `onConnectorError`.

## Backpressure and the Bounded Queue (CDC-M1)

All three connectors' event queues are `ArrayBlockingQueue`s (capacity `event.queue.capacity`, default 10000): when full, the producer side blocks in 50ms slices instead of growing unboundedly; when the connector stops/is interrupted, the producer stops enqueuing — MySQL/PG interrupt the current event processing with an exception so the position **never skips past undelivered events** (after a restart it re-reads from the last committed position: at-least-once). The polling connector's un-enqueued rows simply sit below the watermark and are rescanned next round.

## Minimal Sample (MySQL binlog; isomorphic with `CDCIntegrationExamplesTest`)

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
        .pollingIntervalMs(0)                       // pure pulling: the scheduler stays off
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
        connector.commit(pos);                      // advance the position to avoid reprocessing
    }
}
manager.stop().join();
```

The polling connector's equivalent (matching `DatabasePollingH2IntegrationTest`):

```java
CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("h2-poll")
        .username("sa").password("")
        .batchSize(50)
        .pollingIntervalMs(0)
        .property("jdbc.url", "jdbc:h2:mem:cdc")
        .property("driver.class", "org.h2.Driver")
        .property("tables", "audit")
        .property("timestamp.column", "updated_at")
        .property("snapshot.enabled", true)         // enable explicitly when existing rows are needed
        .build();
var connector = CDCConnectorFactory.createDatabasePolling(cfg);
```

## Bridging into the Streaming API

```java
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.cdc.CDCSource;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import io.github.cuihairu.redis.streaming.cdc.mq.ChangeEventQueueSink;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;

// 1) a CDC connector -> StreamSource (a bounded drain: returns after maxIdlePolls consecutive empty polls)
CDCSource source = new CDCSource(connector);                 // (connector, 100ms, 3)
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
DataStream<ChangeEvent> stream = env.addSource(source);      // in-memory engine: addSource runs run() immediately and buffers records
stream.map(ChangeEvent::getAfterData).print();

// 2) ChangeEvent -> an MQ topic (self-describing payload, the key as the partition key; send failures rethrow for retry/DLQ)
stream.addSink(new ChangeEventQueueSink(producer, "cdc-orders"));   // the default 10s send timeout
```

- `cdc.CDCSource`: wires any `CDCConnector` into core's `StreamSource`; `run()` starts an unstarted connector first, and `cancel()` calls `connector.stop()`.
- `cdc.mq.ChangeEventQueueSink`: implements core's `StreamSink<ChangeEvent>` (cdc depends on mq as `implementation`, see `cdc/build.gradle`).
- The connectors themselves (MySQL binlog / PG logical replication / JDBC polling) and `CDCManager` are unchanged; this module never consumes Redis Streams directly — after the consumption side writes into MQ via `ChangeEventQueueSink`, the runtime's `RedisStreamExecutionEnvironment.fromMqTopic(topic, consumerGroup)` takes over.

## References
- Design.md · [source-sink.md](source-sink.md) · [MQ.md](MQ.md)
