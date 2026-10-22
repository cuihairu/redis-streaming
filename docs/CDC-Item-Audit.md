# The MySQL CDC Item Anti-Cheat (Three Gates)

This page describes a binlog-CDC-based in-game item anti-cheat chain: using the repo's existing join / CEP operators (`docs/Join-CEP-Operators-Design.md`, the phase-1 operator form) it organizes the "item change stream" and the "business transaction stream" into three serial gates, each producing an alert when hit. The complete runnable example lives in `examples/.../streaming/itemaudit/` (a simulated binlog, no real database).

## Threat Model: Why the Audit Data Must Come from the Binlog

What we defend against is **developers/ops modifying items by connecting straight to the database, bypassing the business API**. The characteristics of this cheat:

- No business transaction records (orders/payments/grants) — only row changes to the `item_bag` table;
- Application logs and API auditing see nothing — every application-side instrumentation is bypassed;
- The only unforgeable trace is the MySQL binlog: every data change (via the API or a direct connection) must land in the binlog.

So the first gate is not code but **source selection**: the change facts trust only the binlog replication channel (`CDCSource` + `CDCConnector`), the audit stream is physically isolated from the business write path, and the application layer cannot conceal or tamper with its own traces. This is the root on which the whole chain stands.

## The Three Gates

### Gate 1: Data Trust (the CDC source)

```
MySQL binlog ──CDCConnector──▶ CDCSource ──▶ the ChangeEvent stream (the item_bag and transaction tables)
```

- Real deployment: `MySQLBinlogCDCConnector` (a hand-written reader in the repo, on `mysql-binlog-connector-java`, managing the binlog file name + offset position). Why hand-written rather than Debezium: Debezium needs an external Kafka + Connect cluster — a large ops surface; the hand-written reader aligns natively with this framework's `ChangeEvent` contract at zero extra infrastructure — the simplest choice.
- Example/test: `SimulatedItemBinlogConnector` (the same interface, scripted event replay). Switching to a real database replaces exactly the one connector class; the three gates' code changes zero.
- Topology requirements: the binlog user needs `REPLICATION SLAVE`/`REPLICATION CLIENT` privileges; `binlog_format=ROW` and `binlog_row_image=FULL` (the reconciliation gate needs `afterData`'s full-row image).

### Gate 2: Reconciliation (the essential defense)

```
item_bag changes (LEFT)──┐
                        ├─ keyBy(player|item) ─▶ StreamJoinOperator (LEFT OUTER, ±5s)
transaction records (RIGHT)─┘                 ─▶ an unmatched LEFT → the RECON_MISMATCH alert
```

- **The closed-world assumption**: every legitimate item change must correspond to a business transaction (ordering, payment, ops grants, system compensations… all through business tables). An item change with no matching transaction within ±5s = an anomaly. This assumption turns "detect cheating" into "detect unmatched rows" — it relies on no knowledge of cheat signatures and is **equally effective against unknown techniques**, which is why reconciliation is the essence.
- The implementation is exactly `join.operator.StreamJoinOperator`: both sides map into `Envelope`s (joinKey=`player_id|item_id`, the timestamp from the binlog event time) merged into one keyed stream, `JoinType.LEFT` + `JoinWindow.of(±5s)`. An unmatched LEFT emits immediately (the B-36 semantics: alert first, then re-pair when the late peer arrives).
- **Why LEFT and not INNER**: INNER would silently swallow changes without transactions; LEFT makes "no match" itself an output. The reverse direction (transactions without item changes, e.g. rolled-back payments) is legitimate, and LEFT does not false-positive on it.

### Gate 3: CEP Behavior Boundaries (the whitelist)

The reconciliation gate catches "changes without transactions", but **cheating with transactions** (abusing the ops grant API, internal accounts topping up related players) reconciles — that is what behavior boundaries constrain. The item change stream keys by `player_id`, with three `PatternSequenceProcessFunction`s in parallel:

| Pattern | Shape | What it catches |
|---|---|---|
| `OFF_HOURS` | a single-step predicate: event time ∉ [09:00, 21:00) UTC | any change outside business hours (legitimate ops does not modify items at 3am) |
| `QUANTITY_SPIKE` | a single-step predicate: |delta| ≥ 1000 | per-change magnitude violations (a legitimate single grant is far smaller) |
| `PROBE_SEQUENCE` | `times(3).within(60s)`, delta ∈ [1,10] | repeated small-amount probing — footprinting the audit thresholds/response times |

Single-step patterns are semantically "whitelist filters" (an hours whitelist, a magnitude whitelist); the sequence pattern catches temporal behavior. The three are independent — one's false positives never implicate the others — and thresholds tune per business reality.

## Why It Works: Reconciliation Is the Essence, the Whitelist Is the Boundary

- **The reconciliation gate's power is its weak assumption**: it does not guess who cheats or how; it only requires "legitimate changes have transactions". A developer's direct-DB `UPDATE item_bag SET count=count+9999` looks identical in the binlog to a legitimate write — but it matches no transaction and must alert. **It cannot be bypassed**: to avoid the reconciliation gate, the cheater must also forge transactions; and the transaction table is itself inside the binlog audit scope, so the forged rows leave binlog traces too (a complete post-hoc evidence chain).
- **The CEP gate is the second layer**: even with a legitimate transaction shell (a real grant API abused), the hours/magnitude/probing patterns constrain "abnormal use of legitimate interfaces". The whitelist defines the business-reasonable boundary; crossing it merits manual review.
- The two layers complement: reconciliation catches changes outside the books (certain hits); CEP narrows anomalies inside the books (probabilistic hits).

## Limits and False Positives (stated honestly)

- **Alerts are at-least-once**: a LEFT join's late peer alerts first and re-pairs later; overlapping CEP windows can match repeatedly. The alert side dedups by `(type, player, item, detail)` for display.
- **Write order affects latency**: legitimate writes should land the transaction row before the item row in the binlog (usually natural within one DB transaction, though they are two binlog events); an item row landing first alerts first and then reconciles when the peer arrives — **not a missed alert, but an early one**.
- **Join buffers are cleaned by event time** (`stateRetentionTime`): it must exceed the deployment's actual worst "binlog→audit" latency; the example uses 25h to cover the scripted event-time span, and real deployments configure for the worst binlog backlog (less than the window span is rejected by `JoinConfig.validate()`).
- **Reconciliation depends on transaction completeness**: if the business has a legitimate "change items without the transaction table" path (should it exist? — that is the first thing to audit), it must either write transactions or join the whitelist's source tables, otherwise the alerts never stop.
- **From phase 2**: the join/CEP operators' buffer state lives in keyed state (`__operator:join:buffers` / `__operator:cep:partials`), persisted with checkpoint snapshots; after an instance restart it recovers by at-least-once recomputation, and unfinished window state is no longer lost (phase 1's partition-local memory boundary is lifted — see `docs/Join-CEP-Operators-Design.md`).

## Running It

```bash
# end-to-end demo (a simulated binlog, no Redis/MySQL needed)
./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.streaming.itemaudit.ItemAuditDemo

# gate-level unit tests (the in-memory engine)
./gradlew :examples:test --tests "ItemAuditPipelineTest"
```

The demo runs 5 scenarios: a legitimate pairing (silent), a direct-DB item change (RECON_MISMATCH + QUANTITY_SPIKE), an off-hours grant with a transaction (OFF_HOURS), an over-quota grant with a transaction (QUANTITY_SPIKE), and three small amounts within a minute (PROBE_SEQUENCE) — 5 alerts in total.

## References

- [Join-CEP-Operators-Design.md](Join-CEP-Operators-Design.md) · [CDC.md](CDC.md) · [exactly-once.md](exactly-once.md)
