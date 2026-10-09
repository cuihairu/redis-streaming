package io.github.cuihairu.redis.streaming.examples.streaming.itemaudit;

import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate-level tests on the in-memory engine (no Redis, no database): each scenario pins
 * which gate fires and which stay silent.
 */
class ItemAuditPipelineTest {

    private static List<Alert> audit(List<ChangeEvent> binlog) {
        List<Alert> alerts = new CopyOnWriteArrayList<>();
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        ItemAuditPipeline.wire(env.fromCollection(binlog), alerts::add);
        return alerts;
    }

    private static ChangeEvent item(String p, String i, long delta, String at) {
        return ItemAuditDemo.item(p, i, delta, Instant.parse(at));
    }

    private static ChangeEvent tx(String p, String i, long delta, String at) {
        return ItemAuditDemo.tx(p, i, delta, Instant.parse(at));
    }

    @Test
    void legitPairStaysSilent() {
        List<ChangeEvent> binlog = List.of(
                tx("p1", "sword", 1, "2026-10-09T10:00:00Z"),
                item("p1", "sword", 1, "2026-10-09T10:00:01Z"));

        List<Alert> alerts = audit(binlog);

        assertTrue(alerts.isEmpty(), "a matched in-hours small grant must not alert, got " + alerts);
    }

    @Test
    void itemChangeWithoutTransactionIsReconMismatch() {
        // below the spike threshold so gate 3 stays silent and gate 2 is isolated
        List<ChangeEvent> binlog = List.of(
                item("p2", "gem", 50, "2026-10-09T10:05:00Z"));

        List<Alert> alerts = audit(binlog);

        assertEquals(1, alerts.size(), "got " + alerts);
        assertEquals(Alert.Type.RECON_MISMATCH, alerts.get(0).getType());
        assertEquals("p2", alerts.get(0).getPlayerId());
    }

    @Test
    void offHoursGrantWithTransactionOnlyFiresBehaviorGate() {
        // 02:00 UTC is outside the 09-21 whitelist; the grant has its transaction and the
        // delta is small, so exactly one OFF_HOURS alert and no recon/spike/probe
        List<ChangeEvent> binlog = List.of(
                tx("p3", "gem", 10, "2026-10-09T02:00:00Z"),
                item("p3", "gem", 10, "2026-10-09T02:00:01Z"));

        List<Alert> alerts = audit(binlog);

        assertEquals(1, alerts.size(), "got " + alerts);
        assertEquals(Alert.Type.OFF_HOURS, alerts.get(0).getType());
    }

    @Test
    void spikeGrantWithTransactionOnlyFiresBehaviorGate() {
        List<ChangeEvent> binlog = List.of(
                tx("p4", "gem", 100_000, "2026-10-09T10:10:00Z"),
                item("p4", "gem", 100_000, "2026-10-09T10:10:01Z"));

        List<Alert> alerts = audit(binlog);

        assertEquals(1, alerts.size(), "got " + alerts);
        assertEquals(Alert.Type.QUANTITY_SPIKE, alerts.get(0).getType());
    }

    @Test
    void threeSmallGrantsWithinAMinuteFireProbeSequence() {
        List<ChangeEvent> binlog = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            binlog.add(tx("p5", "gem", 5, "2026-10-09T10:20:0" + i + "Z"));
            binlog.add(item("p5", "gem", 5, "2026-10-09T10:20:0" + i + ".200Z"));
        }

        List<Alert> alerts = audit(binlog);

        assertEquals(1, alerts.size(), "got " + alerts);
        assertEquals(Alert.Type.PROBE_SEQUENCE, alerts.get(0).getType());
        assertEquals("p5", alerts.get(0).getPlayerId());
    }

    @Test
    void whitelistedHourIsNotFlaggedOffHours() {
        assertTrue(ItemAuditPipeline.inWhitelistHours(
                Instant.parse("2026-10-09T09:00:00Z").toEpochMilli()));
        assertTrue(ItemAuditPipeline.inWhitelistHours(
                Instant.parse("2026-10-09T20:59:00Z").toEpochMilli()));
        assertTrue(!ItemAuditPipeline.inWhitelistHours(
                Instant.parse("2026-10-09T21:00:00Z").toEpochMilli()));
        assertTrue(!ItemAuditPipeline.inWhitelistHours(
                Instant.parse("2026-10-09T08:59:00Z").toEpochMilli()));
    }
}
