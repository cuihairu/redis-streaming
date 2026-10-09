package io.github.cuihairu.redis.streaming.examples.streaming.itemaudit;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.cep.EventSequence;
import io.github.cuihairu.redis.streaming.cep.Pattern;
import io.github.cuihairu.redis.streaming.cep.PatternSequence;
import io.github.cuihairu.redis.streaming.cep.operator.PatternSequenceProcessFunction;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import io.github.cuihairu.redis.streaming.join.JoinConfig;
import io.github.cuihairu.redis.streaming.join.JoinType;
import io.github.cuihairu.redis.streaming.join.JoinWindow;
import io.github.cuihairu.redis.streaming.join.operator.Envelope;
import io.github.cuihairu.redis.streaming.join.operator.StreamJoinOperator;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Item-audit anti-fraud chain over the CDC item feed — three gates in series, one alert
 * funnel:
 *
 * <ol>
 *   <li><b>Gate 1 — trusted input</b> (embodied by the CDCSource seam, not code here):
 *       change facts are only ever read from the binlog replication channel, so a direct
 *       DB write by an insider cannot bypass the audit — it shows up exactly like any
 *       legitimate write.</li>
 *   <li><b>Gate 2 — reconciliation</b>: the essence. Every legitimate item change must
 *       have a matching business transaction (order, pay, admin grant, ...). A LEFT OUTER
 *       join of item changes against the transaction stream flags any change with no
 *       peer: RECON_MISMATCH.</li>
 *   <li><b>Gate 3 — behavior boundary</b>: changes that DO have a transaction can still
 *       be fraudulent (abused admin APIs). Whitelisted bounds catch them: OFF_HOURS
 *       (time of day outside the maintenance window), QUANTITY_SPIKE (single delta beyond
 *       the bound), PROBE_SEQUENCE (a run of small consecutive deltas inside a short
 *       time — probing for detection thresholds).</li>
 * </ol>
 *
 * <p>Alerts are at-least-once: the outer join re-emits when a late peer arrives, and CEP
 * windows may overlap. Legitimate feeds should order the transaction row before the item
 * row (the normal write order) so pairs match inside the join window instead of alerting
 * first and reconciling later.
 */
public final class ItemAuditPipeline {

    /** Binlog table holding the item bag rows (the LEFT / audited side). */
    public static final String ITEM_TABLE = "item_bag";
    /** Binlog table holding business transactions (the RIGHT / reconciling side). */
    public static final String TX_TABLE = "transaction";

    /** Whitelisted change window: 09:00–21:00 UTC. Changes outside are OFF_HOURS. */
    static final LocalTime WHITELIST_START = LocalTime.of(9, 0);
    static final LocalTime WHITELIST_END = LocalTime.of(21, 0);
    /** A single change at or beyond this magnitude is a QUANTITY_SPIKE. */
    static final long SPIKE_THRESHOLD = 1_000L;
    /** Deltas within [1, PROBE_MAX] count as small probes. */
    static final long PROBE_MAX = 10L;
    /** PROBE_SEQUENCE: this many small changes... */
    static final int PROBE_COUNT = 3;
    /** ...inside this window. */
    static final Duration PROBE_WINDOW = Duration.ofSeconds(60);
    /** Reconciliation window: a transaction pairs with item changes within ±5s of it. */
    static final Duration RECON_WINDOW = Duration.ofSeconds(5);

    private ItemAuditPipeline() {
    }

    /** Wires all gates from one binlog stream into the alert sink. */
    public static void wire(DataStream<ChangeEvent> binlog, AlertSink sink) {
        wireReconciliationGate(binlog, sink);
        wireBehaviorGate(binlog, sink);
    }

    // ------------------------------------------------------------------ gate 2

    /**
     * LEFT OUTER join of item changes (LEFT) against business transactions (RIGHT), keyed
     * by {@code player|item}. An item change with no peer in the window is emitted with a
     * null right side — that is the RECON_MISMATCH alert. Matched pairs emit null and are
     * filtered out (no news is good news).
     */
    public static void wireReconciliationGate(DataStream<ChangeEvent> binlog, AlertSink sink) {
        JoinConfig<ChangeEvent, ChangeEvent, String> config = JoinConfig.<ChangeEvent, ChangeEvent, String>builder()
                .joinType(JoinType.LEFT)
                .joinWindow(JoinWindow.of(RECON_WINDOW, RECON_WINDOW))
                .leftKeySelector(ItemAuditPipeline::joinKey)
                .rightKeySelector(ItemAuditPipeline::joinKey)
                .leftTimestampExtractor(e -> e.getTimestamp().toEpochMilli())
                .rightTimestampExtractor(e -> e.getTimestamp().toEpochMilli())
                // buffer retention is judged on EVENT time: it must exceed the largest
                // binlog-to-audit lag the deployment can see (the demo script spans a day,
                // so 25h; sized to the replay/offline-audit worst case, not the 1h default)
                .stateRetentionTime(Duration.ofHours(25).toMillis())
                .build();
        config.validate();

        binlog.filter(e -> isItem(e) || isTx(e))
                .map(e -> isItem(e)
                        ? Envelope.<String, ChangeEvent, ChangeEvent>forLeft(joinKey(e), ts(e), e)
                        : Envelope.<String, ChangeEvent, ChangeEvent>forRight(joinKey(e), ts(e), e))
                .keyBy(Envelope::getJoinKey)
                .process(StreamJoinOperator.asKeyedProcessFunction(config,
                        (l, r) -> r == null
                                ? Alert.reconMismatch(str(l.getAfterData().get("player_id")),
                                        str(l.getAfterData().get("item_id")),
                                        "item change " + str(l.getAfterData().get("delta")) + " has no business transaction in ±"
                                                + RECON_WINDOW.toSeconds() + "s",
                                        ts(l))
                                : null))
                .filter(Objects::nonNull)
                .addSink(sink::send);
    }

    // ------------------------------------------------------------------ gate 3

    /** Three behavior patterns over the item-change stream, all feeding one funnel. */
    public static void wireBehaviorGate(DataStream<ChangeEvent> binlog, AlertSink sink) {
        DataStream<ItemDelta> deltas = binlog.filter(ItemAuditPipeline::isItem).map(ItemDelta::from);
        io.github.cuihairu.redis.streaming.api.stream.KeyedStream<String, ItemDelta> keyed =
                deltas.keyBy(ItemDelta::getPlayerId);

        keyed.process(new PatternSequenceProcessFunction<>(offHoursPattern(), ItemDelta::getTimestampMs))
                .map(seq -> toAlert(Alert.Type.OFF_HOURS, seq))
                .addSink(sink::send);

        keyed.process(new PatternSequenceProcessFunction<>(spikePattern(), ItemDelta::getTimestampMs))
                .map(seq -> toAlert(Alert.Type.QUANTITY_SPIKE, seq))
                .addSink(sink::send);

        keyed.process(new PatternSequenceProcessFunction<>(probePattern(), ItemDelta::getTimestampMs))
                .map(seq -> toAlert(Alert.Type.PROBE_SEQUENCE, seq))
                .addSink(sink::send);
    }

    /** Single-step pattern: any change outside the whitelisted time-of-day window. */
    static PatternSequence<ItemDelta> offHoursPattern() {
        return PatternSequence.begin("offHours",
                Pattern.<ItemDelta>of(d -> !inWhitelistHours(d.getTimestampMs())));
    }

    /** Single-step pattern: any change at or beyond the spike threshold. */
    static PatternSequence<ItemDelta> spikePattern() {
        return PatternSequence.begin("spike",
                Pattern.<ItemDelta>of(d -> Math.abs(d.getDelta()) >= SPIKE_THRESHOLD));
    }

    /** Sequence pattern: PROBE_COUNT small deltas inside PROBE_WINDOW — threshold probing. */
    static PatternSequence<ItemDelta> probePattern() {
        return PatternSequence.begin("probe",
                        Pattern.<ItemDelta>of(d -> {
                            long a = Math.abs(d.getDelta());
                            return a >= 1 && a <= PROBE_MAX;
                        }))
                .times(PROBE_COUNT)
                .within(PROBE_WINDOW);
    }

    static Alert toAlert(Alert.Type type, EventSequence<ItemDelta> match) {
        String detail = match.getEvents().stream()
                .map(d -> d.getDelta() + "@" + d.getTimestampMs())
                .collect(Collectors.joining(", "));
        ItemDelta last = match.getEvents().get(match.getEvents().size() - 1);
        return new Alert(type, last.getPlayerId(), last.getItemId(),
                match.getEvents().size() + " change(s): " + detail, match.getEndTime());
    }

    // ------------------------------------------------------------------ helpers

    static boolean isItem(ChangeEvent e) {
        return ITEM_TABLE.equals(e.getTable());
    }

    static boolean isTx(ChangeEvent e) {
        return TX_TABLE.equals(e.getTable());
    }

    static String joinKey(ChangeEvent e) {
        return str(e.getAfterData().get("player_id")) + "|" + str(e.getAfterData().get("item_id"));
    }

    static long ts(ChangeEvent e) {
        return e.getTimestamp() == null ? System.currentTimeMillis() : e.getTimestamp().toEpochMilli();
    }

    static boolean inWhitelistHours(long epochMs) {
        LocalTime t = LocalTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
        return !t.isBefore(WHITELIST_START) && t.isBefore(WHITELIST_END);
    }

    private static String str(Object o) {
        return String.valueOf(o);
    }
}
