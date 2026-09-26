package io.github.cuihairu.redis.streaming.table;

import java.io.Serializable;

/**
 * Folds one record value into the running aggregate of a grouped table.
 *
 * <p>Mirrors Kafka Streams' {@code Aggregator}: the aggregate produced for a group is
 * threaded through {@code apply} for every record of that group, so implementations
 * must be pure (no shared mutable state).</p>
 *
 * @param <K>  the group key type
 * @param <V>  the record value type
 * @param <VR> the aggregate type
 */
@FunctionalInterface
public interface TableAggregator<K, V, VR> extends Serializable {

    /**
     * Fold {@code value} into {@code aggregate} and return the new aggregate.
     *
     * @param key       the group key
     * @param value     the record value
     * @param aggregate the current aggregate (from the initializer on first use)
     * @return the new aggregate
     */
    VR apply(K key, V value, VR aggregate);
}
