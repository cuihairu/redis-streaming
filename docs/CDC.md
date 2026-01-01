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

## References
- Design.md
