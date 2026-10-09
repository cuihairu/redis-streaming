package io.github.cuihairu.redis.streaming.examples.streaming.itemaudit;

import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import lombok.Value;

/**
 * Flattened view of one item-table change, keyed for the behavior gate. Everything the
 * CEP patterns see comes from {@code afterData} of the binlog row image.
 */
@Value
public class ItemDelta {

    String playerId;
    String itemId;
    long delta;
    long timestampMs;

    public static ItemDelta from(ChangeEvent e) {
        java.util.Map<String, Object> after = e.getAfterData();
        return new ItemDelta(
                String.valueOf(after.get("player_id")),
                String.valueOf(after.get("item_id")),
                ((Number) after.get("delta")).longValue(),
                e.getTimestamp() == null ? System.currentTimeMillis() : e.getTimestamp().toEpochMilli());
    }
}
