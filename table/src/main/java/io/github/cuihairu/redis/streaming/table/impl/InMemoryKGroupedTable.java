package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.table.KGroupedTable;
import io.github.cuihairu.redis.streaming.table.KTable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * In-memory implementation of KGroupedTable.
 *
 * @param <K> The type of the key
 * @param <V> The type of the value
 */
public class InMemoryKGroupedTable<K, V> implements KGroupedTable<K, V> {

    private static final long serialVersionUID = 1L;

    private final InMemoryKTable<?, V> sourceTable;
    private final Function<KTable.KeyValue<?, V>, K> keySelector;

    public <KS> InMemoryKGroupedTable(
            InMemoryKTable<KS, V> sourceTable,
            Function<KTable.KeyValue<KS, V>, K> keySelector) {
        this.sourceTable = sourceTable;
        @SuppressWarnings("unchecked")
        Function<KTable.KeyValue<?, V>, K> castedKeySelector = (Function) keySelector;
        this.keySelector = castedKeySelector;
    }

    @Override
    public <VR> KTable<K, VR> aggregate(
            java.util.function.Supplier<VR> initializer,
            BiFunction<K, V, VR> adder,
            BiFunction<K, V, VR> subtractor) {

        Map<K, VR> aggregates = new ConcurrentHashMap<>();

        sourceTable.getState().forEach((key, value) -> {
            KTable.KeyValue<Object, V> kv = KTable.KeyValue.of(key, value);
            K newKey = keySelector.apply(kv);

            aggregates.compute(newKey, (k, current) -> {
                if (current == null) {
                    current = initializer.get();
                }
                return adder.apply(k, value);
            });
        });

        return new InMemoryKTable<>(aggregates);
    }

    @Override
    public KTable<K, Long> count() {
        Map<K, Long> counts = new ConcurrentHashMap<>();

        sourceTable.getState().forEach((key, value) -> {
            KTable.KeyValue<Object, V> kv = KTable.KeyValue.of(key, value);
            K newKey = keySelector.apply(kv);
            counts.merge(newKey, 1L, Long::sum);
        });

        return new InMemoryKTable<>(counts);
    }

    @Override
    public KTable<K, V> reduce(BiFunction<V, V, V> adder, BiFunction<V, V, V> subtractor) {
        Map<K, V> reduced = new ConcurrentHashMap<>();

        sourceTable.getState().forEach((key, value) -> {
            KTable.KeyValue<Object, V> kv = KTable.KeyValue.of(key, value);
            K newKey = keySelector.apply(kv);

            reduced.compute(newKey, (k, current) -> {
                if (current == null) {
                    return value;
                }
                return adder.apply(current, value);
            });
        });

        return new InMemoryKTable<>(reduced);
    }
}
