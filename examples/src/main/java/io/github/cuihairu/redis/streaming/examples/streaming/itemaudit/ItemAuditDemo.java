package io.github.cuihairu.redis.streaming.examples.streaming.itemaudit;

import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import io.github.cuihairu.redis.streaming.cdc.CDCSource;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runnable demo: a scripted (simulated) binlog runs through the item-audit chain and the
 * alert funnel prints what the gates catch. No database, no Redis — the whole chain is
 * exercised on the in-memory engine with the real CDC source seam.
 *
 * <p>Run: {@code ./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.streaming.itemaudit.ItemAuditDemo}
 *
 * <p>Scenario → expected alerts (5 total):
 * <ol>
 *   <li>legit purchase (tx then item grant, in hours, small) — silent</li>
 *   <li>insider direct-DB grant, no transaction — RECON_MISMATCH + QUANTITY_SPIKE</li>
 *   <li>off-hours admin grant WITH transaction — OFF_HOURS only</li>
 *   <li>huge grant WITH transaction — QUANTITY_SPIKE only</li>
 *   <li>three small grants with transactions inside a minute — PROBE_SEQUENCE</li>
 * </ol>
 */
public class ItemAuditDemo {

    public static void main(String[] args) {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        var binlogStream = env.addSource(new CDCSource(new SimulatedItemBinlogConnector(scenario()), 50L, 3));
        ItemAuditPipeline.wire(binlogStream, new WebhookAlertSink(new LogAlertSink()));
        System.out.println("ItemAuditDemo finished — expected 5 alerts above "
                + "(1: silent legit pair, 2: recon+spike on direct-DB grant, "
                + "3: off-hours grant with tx, 4: spike with tx, 5: probe run)");
    }

    /** The scripted binlog: transactions always precede their item row (real write order). */
    static List<ChangeEvent> scenario() {
        List<ChangeEvent> feed = new ArrayList<>();

        // 1 — legit purchase at 10:00 UTC
        Instant t1 = Instant.parse("2026-10-09T10:00:00Z");
        feed.add(tx("p1", "sword", 1, t1));
        feed.add(item("p1", "sword", 1, t1.plusSeconds(1)));

        // 2 — insider direct-DB grant at 10:05 UTC: no transaction row exists
        feed.add(item("p2", "gem", 5000, Instant.parse("2026-10-09T10:05:00Z")));

        // 3 — admin grant at 02:00 UTC (outside 09-21 whitelist) WITH its transaction
        Instant t3 = Instant.parse("2026-10-09T02:00:00Z");
        feed.add(tx("p3", "gem", 10, t3));
        feed.add(item("p3", "gem", 10, t3.plusSeconds(1)));

        // 4 — huge grant at 10:10 UTC WITH its transaction
        Instant t4 = Instant.parse("2026-10-09T10:10:00Z");
        feed.add(tx("p4", "gem", 100_000, t4));
        feed.add(item("p4", "gem", 100_000, t4.plusSeconds(1)));

        // 5 — probe run: three small grants with transactions inside one minute
        Instant t5 = Instant.parse("2026-10-09T10:20:00Z");
        for (int i = 0; i < 3; i++) {
            feed.add(tx("p5", "gem", 5, t5.plusSeconds(i)));
            feed.add(item("p5", "gem", 5, t5.plusSeconds(i).plusMillis(200)));
        }

        return feed;
    }

    static ChangeEvent item(String playerId, String itemId, long delta, Instant at) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("player_id", playerId);
        after.put("item_id", itemId);
        after.put("delta", delta);
        ChangeEvent e = new ChangeEvent(ChangeEvent.EventType.INSERT, "game", ItemAuditPipeline.ITEM_TABLE, after);
        e.setKey(itemId);
        e.setTimestamp(at);
        e.setSource("simulated-binlog");
        return e;
    }

    static ChangeEvent tx(String playerId, String itemId, long delta, Instant at) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("player_id", playerId);
        after.put("item_id", itemId);
        after.put("delta", delta);
        ChangeEvent e = new ChangeEvent(ChangeEvent.EventType.INSERT, "game_biz", ItemAuditPipeline.TX_TABLE, after);
        e.setKey(itemId);
        e.setTimestamp(at);
        e.setSource("simulated-binlog");
        return e;
    }
}
