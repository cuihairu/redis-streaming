# CDC

Module: `cdc/`

Change Data Capture connectors (MySQL Binlog / PostgreSQL logical replication / DB polling).

## Scope
- 捕获数据库变更并转为 `ChangeEvent`（供下游 MQ/聚合/存储等模块消费）
- Connector 生命周期/健康监控由 `CDCManager` 协调

## Key Classes
- `CDCConfiguration` / `CDCConfigurationBuilder`
- `CDCConnector` / `CDCManager`
- `cdc.impl.MySQLBinlogCDCConnector`
- `cdc.impl.PostgreSQLLogicalReplicationCDCConnector`
- `cdc.impl.DatabasePollingCDCConnector`

## Minimal Sample (MySQL binlog; simplified)
```java
import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.CDCManager;
import io.github.cuihairu.redis.streaming.cdc.impl.MySQLBinlogCDCConnector;

CDCConfiguration cfg = CDCConfigurationBuilder.forMySQLBinlog("mysql-binlog")
        .username("root")
        .password("secret")
        .mysqlHostname("127.0.0.1")
        .mysqlPort(3306)
        .property("table.includes", java.util.List.of("test_db.orders"))
        .build();

var connector = new MySQLBinlogCDCConnector(cfg);
CDCManager manager = new CDCManager();
manager.addConnector(connector);
manager.start().join();

var events = connector.poll();
// ... handle events ...
```

## 接入流式 API 的桥接(0.3 起)

```java
// 1) CDC 连接器 -> StreamSource(有界排空:连续 maxIdlePolls 次空 poll 即返回)
CDCSource source = new CDCSource(connector);                 // implements StreamSource<ChangeEvent>
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.addSource(source).map(e -> e.getAfterData()).print();

// 2) ChangeEvent -> MQ topic(自描述 payload,key 作分区键,发送失败上抛供重试/DLQ)
stream.addSink(new ChangeEventQueueSink(producer, "cdc-orders"));
```

- `cdc.CDCSource`:把任意 `CDCConnector` 接入 core `StreamSource`;`cancel()` 会 `connector.stop()`。
- `cdc.mq.ChangeEventQueueSink`:实现 core `StreamSink<ChangeEvent>`(cdc 模块新增对 mq 的 implementation 依赖)。

> 说明:连接器本体(MySQL binlog/PG 逻辑复制/JDBC 轮询)与 `CDCManager` 不变;本模块仍不直接实现 Redis Stream 消费——如需消费侧,用 `ChangeEventQueueSink` 写入 MQ 后由 runtime `fromMqTopic` 承接。

## References
- Design.md · [source-sink.md](source-sink.md) · [MQ.md](MQ.md)
