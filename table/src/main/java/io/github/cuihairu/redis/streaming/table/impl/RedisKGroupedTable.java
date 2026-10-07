package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.table.KGroupedTable;
import io.github.cuihairu.redis.streaming.table.KTable;
import io.github.cuihairu.redis.streaming.table.TableAggregator;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public final class RedisKGroupedTable<KS, K, V> implements KGroupedTable<K, V> {
    private static final long serialVersionUID = 1L;

    private final RedisKTable<KS, V> sourceTable;
    private final Function<KTable.KeyValue<KS, V>, K> keySelector;

    private RedisKGroupedTable(RedisKTable<KS, V> sourceTable, Function<KTable.KeyValue<KS, V>, K> keySelector) {
        this.sourceTable = Objects.requireNonNull(sourceTable, "sourceTable");
        this.keySelector = Objects.requireNonNull(keySelector, "keySelector");
    }

    static <KS, K, V> RedisKGroupedTable<KS, K, V> from(
            RedisKTable<KS, V> sourceTable,
            Function<KTable.KeyValue<KS, V>, K> keySelector) {
        return new RedisKGroupedTable<>(sourceTable, keySelector);
    }

    @Override
    public <VR> KTable<K, VR> aggregate(Supplier<VR> initializer,
                                       TableAggregator<K, V, VR> adder,
                                       TableAggregator<K, V, VR> subtractor) {
        Objects.requireNonNull(initializer, "initializer");
        Objects.requireNonNull(adder, "adder");

        Map<KS, V> snapshot = sourceTable.getState();
        Map<K, VR> aggregates = new HashMap<>();
        Class<K> inferredKeyClass = null;
        Class<VR> inferredValueClass = null;

        for (Map.Entry<KS, V> entry : snapshot.entrySet()) {
            K key = keySelector.apply(KTable.KeyValue.of(entry.getKey(), entry.getValue()));
            if (key == null) {
                continue;
            }
            if (inferredKeyClass == null) {
                @SuppressWarnings("unchecked")
                Class<K> c = (Class<K>) key.getClass();
                inferredKeyClass = c;
            }

            VR current = aggregates.containsKey(key) ? aggregates.get(key) : initializer.get();
            VR aggregate = adder.apply(key, entry.getValue(), current);
            aggregates.put(key, aggregate);
            if (inferredValueClass == null && aggregate != null) {
                @SuppressWarnings("unchecked")
                Class<VR> c = (Class<VR>) aggregate.getClass();
                inferredValueClass = c;
            }
        }

        return createResultTable("aggregate", inferredKeyClass, inferredValueClass, aggregates);
    }

    @Override
    public KTable<K, Long> count() {
        Map<KS, V> snapshot = sourceTable.getState();
        Map<K, Long> counts = new HashMap<>();
        Class<K> inferredKeyClass = null;

        for (Map.Entry<KS, V> entry : snapshot.entrySet()) {
            K key = keySelector.apply(KTable.KeyValue.of(entry.getKey(), entry.getValue()));
            if (key == null) {
                continue;
            }
            if (inferredKeyClass == null) {
                @SuppressWarnings("unchecked")
                Class<K> c = (Class<K>) key.getClass();
                inferredKeyClass = c;
            }
            counts.merge(key, 1L, Long::sum);
        }

        return createResultTable("count", inferredKeyClass, Long.class, counts);
    }

    @Override
    public KTable<K, V> reduce(BiFunction<V, V, V> adder, BiFunction<V, V, V> subtractor) {
        Objects.requireNonNull(adder, "adder");

        Map<KS, V> snapshot = sourceTable.getState();
        Map<K, V> reduced = new HashMap<>();
        Class<K> inferredKeyClass = null;
        Class<V> inferredValueClass = null;

        for (Map.Entry<KS, V> entry : snapshot.entrySet()) {
            K key = keySelector.apply(KTable.KeyValue.of(entry.getKey(), entry.getValue()));
            if (key == null) {
                continue;
            }
            if (inferredKeyClass == null) {
                @SuppressWarnings("unchecked")
                Class<K> c = (Class<K>) key.getClass();
                inferredKeyClass = c;
            }

            V v = entry.getValue();
            V current = reduced.get(key);
            V next = current == null ? v : adder.apply(current, v);
            reduced.put(key, next);
            if (inferredValueClass == null && next != null) {
                @SuppressWarnings("unchecked")
                Class<V> c = (Class<V>) next.getClass();
                inferredValueClass = c;
            }
        }

        return createResultTable("reduce", inferredKeyClass, inferredValueClass, reduced);
    }

    private <VR> RedisKTable<K, VR> createResultTable(String opName,
                                                      Class<K> inferredKeyClass,
                                                      Class<VR> inferredValueClass,
                                                      Map<K, VR> values) {
        @SuppressWarnings("unchecked")
        Class<K> keyCls = inferredKeyClass != null ? inferredKeyClass : (Class<K>) Object.class;
        @SuppressWarnings("unchecked")
        Class<VR> valCls = inferredValueClass != null ? inferredValueClass : (Class<VR>) Object.class;

        // random suffix (in newDerivedChild): two group-bys inside the same millisecond used
        // to collide onto one hash; the child is registered in the source's lineage so its
        // generation count stays bounded and delete() reclaims it (B-24)
        return sourceTable.newDerivedChild("groupBy:" + opName, keyCls, valCls, values);
    }
}

