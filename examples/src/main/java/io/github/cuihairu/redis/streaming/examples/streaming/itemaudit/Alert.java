package io.github.cuihairu.redis.streaming.examples.streaming.itemaudit;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

/**
 * One audit alert produced by any of the item-audit gates. Alerts are at-least-once:
 * a windowing or replaying gate may report the same underlying change twice, and the
 * alert sink is expected to tolerate that (dedup on {@code type + playerId + itemId + detail}
 * if the downstream needs exactly-once presentation).
 */
@Data
@AllArgsConstructor
public class Alert implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Type {
        /** Gate 2 (reconciliation): an item-table change with no matching business transaction. */
        RECON_MISMATCH,
        /** Gate 3 (behavior): change happened outside the whitelisted time-of-day window. */
        OFF_HOURS,
        /** Gate 3 (behavior): single change magnitude beyond the whitelisted bound. */
        QUANTITY_SPIKE,
        /** Gate 3 (behavior): a run of small consecutive changes — probing behavior. */
        PROBE_SEQUENCE
    }

    private final Type type;
    private final String playerId;
    private final String itemId;
    private final String detail;
    private final long eventTimestampMs;

    public static Alert reconMismatch(String playerId, String itemId, String detail, long ts) {
        return new Alert(Type.RECON_MISMATCH, playerId, itemId, detail, ts);
    }

    public String jsonLine() {
        return "{\"type\":\"" + type + "\",\"playerId\":\"" + playerId + "\",\"itemId\":\"" + itemId
                + "\",\"detail\":\"" + detail + "\",\"ts\":" + eventTimestampMs + "}";
    }
}
