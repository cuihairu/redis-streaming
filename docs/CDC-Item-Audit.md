# MySQL CDC 道具反作弊（三道闸）

本文描述一条基于 binlog CDC 的游戏道具反作弊链：用仓库现有的 join / CEP 算子（`docs/Join-CEP-Operators-Design.md`，phase 1 operatorization）把"道具变更流"和"业务流水流"组织成三道串联的闸门，任何一道命中即产出告警。完整可跑示例在 `examples/.../streaming/itemaudit/`（模拟 binlog，免真实库）。

## 威胁模型：为什么审计数据必须来自 binlog

防的是**开发/运营人员绕过业务 API 直连数据库**私改道具。这类作弊的特点：

- 不产生业务流水（订单/支付/发放记录）——只有 `item_bag` 表的行变更；
- 应用层日志、API 审计完全无感——绕过了所有应用侧埋点；
- 唯一不可绕过的痕迹是 MySQL binlog：任何数据变更（无论经 API 还是直连）都必须落 binlog。

因此第一条闸不是代码而是**数据源选择**：变更事实只认 binlog 复制通道（`CDCSource` + `CDCConnector`），审计流与业务写路径物理隔离，应用层无法掩盖或篡改自己的作案记录。这是整条链成立的根。

## 三道闸

### 闸 1：数据可信（CDC 源）

```
MySQL binlog ──CDCConnector──▶ CDCSource ──▶ ChangeEvent 流（item_bag + transaction 两张表）
```

- 真实部署：`MySQLBinlogCDCConnector`（仓内自研 reader，`mysql-binlog-connector-java`，虚拟槽 `FilePosition` 位点管理）。选择自研而非 Debezium 的理由：Debezium 需要外部 Kafka + Connect 集群，运维面大；自研 reader 与本框架的 `ChangeEvent` 契约原生对齐，零额外基础设施——择最简。
- 示例/测试：`SimulatedItemBinlogConnector`（同一接口，脚本化事件回放）。切换到真实库只需替换 connector 一个类，三道闸代码零改动。
- 拓扑要求：binlog 用户需 `REPLICATION SLAVE`/`REPLICATION CLIENT` 权限；`binlog_format=ROW`、`binlog_row_image=FULL`（对账闸需要 `afterData` 全行镜像）。

### 闸 2：对账（本质防线）

```
item_bag 变更（LEFT）──┐
                      ├─ keyBy(player|item) ─▶ StreamJoinOperator（LEFT OUTER，±5s）
transaction 流水（RIGHT）─┘                 ─▶ 无 peer 的 LEFT → RECON_MISMATCH 告警
```

- **闭世界假设**：每一笔合法的道具变更必然对应一条业务流水（下单、支付、运营发放、系统补偿……全走业务表）。item 变更在 ±5s 窗口内对不上任何流水 = 异常。这个假设把"检测作弊"转化为"检测对不上的行"——不依赖任何作弊特征知识，**对未知手法同样有效**，所以它是对账是本质。
- 实现即 `join.operator.StreamJoinOperator`：两侧流 map 成 `Envelope`（joinKey=`player_id|item_id`，时间戳取 binlog 事件时间）合流进一个 keyed 流，`JoinType.LEFT` + `JoinWindow.of(±5s)`。unmatched LEFT 立即发射（B-36 语义：先告警、晚到 peer 再补配对）。
- **为什么要 LEFT 而不是 INNER**：INNER 会把无流水的变更静默吞掉；LEFT 把"没有匹配"本身变成输出。反过来（有流水无道具变更，如支付失败回滚）是合法形态，LEFT 不误伤。

### 闸 3：CEP 行为边界（白名单）

对账闸抓"没有流水的改"，但**带流水的作弊**（滥用运营发放 API、内部账号给关联玩家充值）对得上账——这类靠行为边界约束。道具变更流按 `player_id` 分 key，三个 `PatternSequenceProcessFunction` 并联：

| 模式 | 形状 | 抓什么 |
|---|---|---|
| `OFF_HOURS` | 单步谓词：事件时间 ∉ [09:00, 21:00) UTC | 维护窗口外的任何变更（正常运营不需要凌晨改道具） |
| `QUANTITY_SPIKE` | 单步谓词：|delta| ≥ 1000 | 单次量级越界（正常单笔发放远小于此） |
| `PROBE_SEQUENCE` | `times(3).within(60s)`，delta ∈ [1,10] | 连续小额试探——探测审计阈值/响应时间的踩点行为 |

单步模式语义上就是"白名单过滤器"（时段白名单、量级白名单），序列模式抓时序行为。三者互相独立、误报互不牵连，阈值按业务实际调整。

## 为何能防住私改：对账是本质，白名单是边界

- **对账闸的威力在于它的假设极弱**：它不猜"谁会作弊、怎么作弊"，只要求"合法变更必有流水"。开发直连 DB 的 `UPDATE item_bag SET count=count+9999` 在 binlog 里与正常写入形态完全一致——但对不上流水，必然告警。**绕不过去**：要不被对账闸发现，作弊者必须同时伪造流水；而流水表本身也在 binlog 审计范围内，伪造流水的行变更同样会留下 binlog 痕迹（事后追责证据链完整）。
- **CEP 闸是第二层**：即便作弊者有合法流水外壳（滥用真实发放接口），时段/量级/试探模式约束了"合法接口的异常使用"。白名单定义了业务上合理的边界，越界即值得人工复核。
- 两层互补：对账抓"账外之改"（必然命中），CEP 抹"账内之异"（概率命中）。

## 极限与误报（如实）

- **告警是 at-least-once**：LEFT join 晚到 peer 会先告警后补配对；CEP 窗口重叠可重复匹配。告警侧按 `(type, player, item, detail)` 去重展示。
- **写入顺序影响时延**：正常写入应保证流水行先于道具行落 binlog（同一 DB 事务内通常天然如此，但两行是两条 binlog event）；道具行先落会先告警、peer 到达后对平——**不是漏报，是先报后平**。
- **join 缓冲按事件时间清理**（`stateRetentionTime`）：必须大于部署实际的最大"binlog→审计"延迟；示例里用 25h 覆盖脚本化事件时间跨度，真实部署按 binlog 积压最坏情况配置（小于窗口跨度会被 `JoinConfig.validate()` 拒绝）。
- **对账依赖流水完整性**：若业务本身存在"改道具不走流水表"的合法路径（应存在吗？——这恰是要审计的第一件事），须先补流水或加白名单源表，否则持续误报。
- **相位 1 边界**：join/CEP 算子的缓冲状态是分区内存态、不进 checkpoint（见 `docs/Join-CEP-Operators-Design.md`），实例重启丢的是未完成的窗口状态，可能漏掉重启瞬间的匹配——Redis runtime 部署下对账闸可配合 MQ 重放位点补扫。

## 跑法

```bash
# 端到端 demo（模拟 binlog，无 Redis/MySQL 依赖）
./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.streaming.itemaudit.ItemAuditDemo

# 闸级单测（in-memory 引擎）
./gradlew :examples:test --tests "ItemAuditPipelineTest"
```

demo 场景 5 组：合法配对（静默）、直连 DB 私改（RECON_MISMATCH + QUANTITY_SPIKE）、凌晨带流水授权（OFF_HOURS）、超量带流水（QUANTITY_SPIKE）、一分钟三连小额（PROBE_SEQUENCE）——共 5 条告警。

## References

- [Join-CEP-Operators-Design.md](Join-CEP-Operators-Design.md) · [CDC.md](CDC.md) · [exactly-once.md](exactly-once.md)
