package io.github.cuihairu.redis.streaming.table.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.table.KGroupedTable;
import io.github.cuihairu.redis.streaming.table.KTable;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Redis-backed implementation of KTable for production use.
 *
 * This implementation persists the table state in Redis Hash,
 * providing durability and distributed access to the table.
 *
 * <p>Derivations ({@code mapValues}/{@code filter}/{@code join}/{@code leftJoin} and
 * grouped aggregations) materialize their result into a new Redis Hash under this
 * table's namespace. Each materialization is registered in the lineage hash
 * {@code <tableName>:__derived} so that it is never an orphan (B-24): at most
 * {@link #getDerivedTableRetention()} live generations are kept per operation — older
 * generations are cascade-deleted together with their own derivations — and
 * {@link #delete()} removes this table together with its whole derivation tree.</p>
 *
 * @param <K> The type of the key
 * @param <V> The type of the value
 */
@Slf4j
public class RedisKTable<K, V> implements KTable<K, V> {

    private static final long serialVersionUID = 1L;

    /**
     * Default number of live materialized generations kept per (table, operation).
     */
    public static final int DEFAULT_DERIVED_RETENTION = 8;

    /**
     * Suffix of the hash that records this table's materialized derivations.
     * Value fields are child table names, values are {@code {"o":op,"t":millis}}.
     */
    static final String DERIVED_LINEAGE_SUFFIX = ":__derived";

    private static final ObjectMapper LINEAGE = new ObjectMapper();

    private final RedissonClient redissonClient;
    private final String tableName;
    private final ObjectMapper objectMapper;
    private final Class<K> keyClass;
    private final Class<V> valueClass;
    private int derivedRetention = DEFAULT_DERIVED_RETENTION;
    private Duration derivedTableTtl;

    /**
     * Create a Redis-backed KTable
     *
     * @param redissonClient The Redisson client
     * @param tableName The name of the table (Redis Hash key)
     * @param keyClass The class of the key type
     * @param valueClass The class of the value type
     */
    public RedisKTable(RedissonClient redissonClient, String tableName,
                       Class<K> keyClass, Class<V> valueClass) {
        this.redissonClient = redissonClient;
        this.tableName = tableName;
        this.objectMapper = new ObjectMapper();
        this.keyClass = keyClass;
        this.valueClass = valueClass;
        log.info("Created RedisKTable: {}", tableName);
    }

    /**
     * Derive a unique Redis key for a transformation result. A bare
     * {@code System.currentTimeMillis()} collided for two calls in the same millisecond
     * (e.g. chained filter/mapValues in a loop), silently merging the two results into
     * one hash — the random suffix makes each derivation a distinct table. Uniqueness is
     * bounded by the lineage retention below, so repeated derivations cannot grow the
     * keyspace without limit.
     */
    private String derivedTableName(String op) {
        return tableName + ":" + op + ":" + System.currentTimeMillis() + "-" + UUID.randomUUID();
    }

    /**
     * Live generations kept per operation on this table (including the newest one).
     * Past that count the oldest same-operation derivations — and their own derivation
     * trees — are deleted. Inherited by tables derived from this one.
     */
    public void setDerivedTableRetention(int maxGenerations) {
        if (maxGenerations < 1) {
            throw new IllegalArgumentException("derived table retention must be >= 1, got " + maxGenerations);
        }
        this.derivedRetention = maxGenerations;
    }

    public int getDerivedTableRetention() {
        return derivedRetention;
    }

    /**
     * Optional TTL applied to every table derived from this one (including the
     * intermediate hashes of grouped aggregations). {@code null} (default) keeps
     * derived tables until retention/lineage cleanup removes them. Inherited by
     * tables derived from this one.
     */
    public void setDerivedTableTtl(Duration ttl) {
        this.derivedTableTtl = ttl;
    }

    public Duration getDerivedTableTtl() {
        return derivedTableTtl;
    }

    /**
     * Create the next materialized generation of this table for {@code op}: a fresh
     * child hash under this table's namespace, registered in this table's lineage and
     * bounded by {@link #getDerivedTableRetention()} live generations of the same
     * operation. The child is registered before it is filled, so a crash mid-fill
     * leaves a registered (and therefore later reclaimed) generation instead of an
     * orphan hash; the TTL is applied after the fill because Redisson cannot expire
     * a hash that has no key in Redis yet.
     */
    <NK, VR> RedisKTable<NK, VR> newDerivedChild(String op, Class<NK> newKeyClass, Class<VR> valueCls,
                                                 Map<NK, VR> content) {
        RedisKTable<NK, VR> child = new RedisKTable<>(redissonClient, derivedTableName(op), newKeyClass, valueCls);
        child.derivedRetention = derivedRetention;
        child.derivedTableTtl = derivedTableTtl;
        registerDerivedLineage(op, child.tableName);
        for (Map.Entry<NK, VR> entry : content.entrySet()) {
            child.put(entry.getKey(), entry.getValue());
        }
        if (derivedTableTtl != null) {
            child.getMap().expire(derivedTableTtl);
        }
        pruneDerivedLineage(op);
        return child;
    }

    /**
     * Get the Redis Hash for this table
     */
    private RMap<String, String> getMap() {
        return redissonClient.getMap(tableName, StringCodec.INSTANCE);
    }

    /**
     * Lineage hash of this table: child table name -> {@code {"o":op,"t":millis}}.
     * Entries that do not parse as lineage JSON (foreign keys sharing the namespace)
     * are skipped, never touched.
     */
    private RMap<String, String> lineageMap() {
        return redissonClient.getMap(tableName + DERIVED_LINEAGE_SUFFIX, StringCodec.INSTANCE);
    }

    private void registerDerivedLineage(String op, String childName) {
        try {
            String value = LINEAGE.writeValueAsString(Map.of("o", op, "t", System.currentTimeMillis()));
            lineageMap().put(childName, value);
        } catch (JsonProcessingException e) {
            // Map.of(String, Object) with String/long values cannot fail to encode
            throw new IllegalStateException("Failed to encode lineage entry for table " + tableName, e);
        }
    }

    /**
     * Enforce the per-operation generation bound: keep the newest
     * {@link #getDerivedTableRetention()} same-operation entries, cascade-delete the rest
     * (each stale child together with its own derivation tree and lineage hash).
     */
    private void pruneDerivedLineage(String op) {
        Map<String, String> raw = lineageMap().readAllMap();
        if (raw.isEmpty()) {
            return;
        }
        List<LineageEntry> sameOp = new ArrayList<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            LineageEntry parsed = parseLineage(entry.getKey(), entry.getValue());
            if (parsed != null && op.equals(parsed.op())) {
                sameOp.add(parsed);
            }
        }
        if (sameOp.size() <= derivedRetention) {
            return;
        }
        sameOp.sort(Comparator.comparingLong(LineageEntry::createdAt).reversed());
        for (int i = derivedRetention; i < sameOp.size(); i++) {
            LineageEntry stale = sameOp.get(i);
            lineageMap().remove(stale.name());
            cascadeDelete(redissonClient, stale.name(), new HashSet<>());
            log.debug("Pruned stale generation {} of {} on table {}", stale.name(), op, tableName);
        }
    }

    /**
     * Decode one lineage entry; returns {@code null} for anything that is not ours
     * (foreign or legacy values) so cleanup can never misread a foreign key.
     */
    private static LineageEntry parseLineage(String name, String value) {
        JsonNode node;
        try {
            node = LINEAGE.readTree(value);
        } catch (JsonProcessingException e) {
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode opNode = node.get("o");
        JsonNode timeNode = node.get("t");
        if (opNode == null || !opNode.isTextual() || timeNode == null || !timeNode.isNumber()) {
            return null;
        }
        return new LineageEntry(name, opNode.textValue(), timeNode.longValue());
    }

    /**
     * Delete a table together with its whole derivation tree: each recorded child is
     * removed recursively (its own lineage hash first), then the lineage hash and the
     * table hash itself. The visited set guards against cyclic foreign entries.
     */
    static void cascadeDelete(RedissonClient client, String name, Set<String> visited) {
        if (!visited.add(name)) {
            return;
        }
        RMap<String, String> lineage = client.getMap(name + DERIVED_LINEAGE_SUFFIX, StringCodec.INSTANCE);
        Map<String, String> children = lineage.readAllMap();
        for (String childName : children.keySet()) {
            cascadeDelete(client, childName, visited);
        }
        lineage.delete();
        client.getMap(name, StringCodec.INSTANCE).delete();
    }

    private record LineageEntry(String name, String op, long createdAt) {
    }

    /**
     * Update or insert a key-value pair
     */
    public void put(K key, V value) {
        try {
            RMap<String, String> map = getMap();
            String keyStr = serializeKey(key);

            if (value == null) {
                map.remove(keyStr);
                log.debug("Removed key from table {}: {}", tableName, keyStr);
            } else {
                String valueStr = serializeValue(value);
                map.put(keyStr, valueStr);
                log.debug("Put key-value to table {}: {} = {}", tableName, keyStr, valueStr);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize key-value for table {}", tableName, e);
            throw new RuntimeException("Serialization failed", e);
        }
    }

    /**
     * Get the value for a key
     */
    public V get(K key) {
        try {
            RMap<String, String> map = getMap();
            String keyStr = serializeKey(key);
            String valueStr = map.get(keyStr);

            if (valueStr == null) {
                return null;
            }

            return deserializeValue(valueStr);
        } catch (IOException e) {
            log.error("Failed to deserialize value from table {}", tableName, e);
            throw new RuntimeException("Deserialization failed", e);
        }
    }

    /**
     * Get all entries in the table
     */
    public Map<K, V> getState() {
        try {
            RMap<String, String> map = getMap();
            Map<K, V> result = new HashMap<>();

            // readAllMap (single HGETALL) instead of entrySet (HSCAN cursor): the cursor
            // walk could observe a key deleted mid-iteration as a null entry value and
            // feed a torn snapshot to filter/join/groupBy (B-45)
            for (Map.Entry<String, String> entry : map.readAllMap().entrySet()) {
                K key = deserializeKey(entry.getKey());
                V value = deserializeValue(entry.getValue());
                result.put(key, value);
            }

            return result;
        } catch (IOException e) {
            log.error("Failed to get state from table {}", tableName, e);
            throw new RuntimeException("Deserialization failed", e);
        }
    }

    /**
     * Get the number of entries in the table
     */
    public int size() {
        return getMap().size();
    }

    /**
     * Clear all entries from the table. Derived tables keep their snapshots —
     * only {@link #delete()} reclaims the derivation tree.
     */
    public void clear() {
        getMap().clear();
        log.info("Cleared table: {}", tableName);
    }

    /**
     * Delete the entire table from Redis, together with every materialized
     * derivation registered in its lineage (recursive: each child is deleted with
     * its own derivations).
     */
    public void delete() {
        cascadeDelete(redissonClient, tableName, new HashSet<>());
        log.info("Deleted table and its derivation tree: {}", tableName);
    }

    @Override
    public <VR> KTable<K, VR> mapValues(Function<V, VR> mapper) {
        try {
            Map<K, VR> temp = new HashMap<>();
            Class<VR> inferred = null;
            Map<K, V> state = getState();
            for (Map.Entry<K, V> entry : state.entrySet()) {
                VR newValue = mapper.apply(entry.getValue());
                temp.put(entry.getKey(), newValue);
                if (inferred == null && newValue != null) {
                    @SuppressWarnings("unchecked")
                    Class<VR> c = (Class<VR>) newValue.getClass();
                    inferred = c;
                }
            }
            @SuppressWarnings("unchecked")
            Class<VR> valueCls = inferred != null ? inferred : (Class<VR>) Object.class;
            return newDerivedChild("mapValues", keyClass, valueCls, temp);
        } catch (Exception e) {
            log.error("Failed to map values for table {}", tableName, e);
            throw new RuntimeException("Map values failed", e);
        }
    }

    @Override
    public <VR> KTable<K, VR> mapValues(BiFunction<K, V, VR> mapper) {
        try {
            Map<K, VR> temp = new HashMap<>();
            Class<VR> inferred = null;
            Map<K, V> state = getState();
            for (Map.Entry<K, V> entry : state.entrySet()) {
                VR newValue = mapper.apply(entry.getKey(), entry.getValue());
                temp.put(entry.getKey(), newValue);
                if (inferred == null && newValue != null) {
                    @SuppressWarnings("unchecked")
                    Class<VR> c = (Class<VR>) newValue.getClass();
                    inferred = c;
                }
            }
            @SuppressWarnings("unchecked")
            Class<VR> valueCls = inferred != null ? inferred : (Class<VR>) Object.class;
            return newDerivedChild("mapValues", keyClass, valueCls, temp);
        } catch (Exception e) {
            log.error("Failed to map values for table {}", tableName, e);
            throw new RuntimeException("Map values failed", e);
        }
    }

    @Override
    public KTable<K, V> filter(BiFunction<K, V, Boolean> predicate) {
        try {
            Map<K, V> temp = new HashMap<>();
            Map<K, V> state = getState();
            for (Map.Entry<K, V> entry : state.entrySet()) {
                if (predicate.apply(entry.getKey(), entry.getValue())) {
                    temp.put(entry.getKey(), entry.getValue());
                }
            }
            return newDerivedChild("filter", keyClass, valueClass, temp);
        } catch (Exception e) {
            log.error("Failed to filter table {}", tableName, e);
            throw new RuntimeException("Filter failed", e);
        }
    }

    @Override
    public <VO, VR> KTable<K, VR> join(KTable<K, VO> other, BiFunction<V, VO, VR> joiner) {
        Map<K, VO> otherState = null;
        RedisKTable<K, VO> otherTable = null;
        if (other instanceof RedisKTable<?, ?>) {
            @SuppressWarnings("unchecked")
            RedisKTable<K, VO> t = (RedisKTable<K, VO>) other;
            otherTable = t;
        } else if (other instanceof InMemoryKTable<?, ?>) {
            @SuppressWarnings("unchecked")
            InMemoryKTable<K, VO> t = (InMemoryKTable<K, VO>) other;
            otherState = t.getState();
        } else {
            throw new UnsupportedOperationException("Can only join with RedisKTable or InMemoryKTable");
        }

        try {
            Map<K, VR> temp = new HashMap<>();
            Class<VR> inferred = null;
            Map<K, V> state = getState();
            for (Map.Entry<K, V> entry : state.entrySet()) {
                VO otherValue = otherTable != null ? otherTable.get(entry.getKey()) : otherState.get(entry.getKey());
                if (otherValue != null) {
                    VR joinedValue = joiner.apply(entry.getValue(), otherValue);
                    temp.put(entry.getKey(), joinedValue);
                    if (inferred == null && joinedValue != null) {
                        @SuppressWarnings("unchecked")
                        Class<VR> c = (Class<VR>) joinedValue.getClass();
                        inferred = c;
                    }
                }
            }
            @SuppressWarnings("unchecked")
            Class<VR> valueCls = inferred != null ? inferred : (Class<VR>) Object.class;
            return newDerivedChild("join", keyClass, valueCls, temp);
        } catch (Exception e) {
            // otherTable is null when the peer is an InMemoryKTable; dereferencing it here used to
            // throw a bare NPE that replaced the original exception (B-23)
            String otherName = otherTable != null ? otherTable.tableName : "in-memory table";
            log.error("Failed to join tables {} and {}", tableName, otherName, e);
            throw new RuntimeException("Join failed", e);
        }
    }

    @Override
    public <VO, VR> KTable<K, VR> leftJoin(KTable<K, VO> other, BiFunction<V, VO, VR> joiner) {
        Map<K, VO> otherState = null;
        RedisKTable<K, VO> otherTable = null;
        if (other instanceof RedisKTable<?, ?>) {
            @SuppressWarnings("unchecked")
            RedisKTable<K, VO> t = (RedisKTable<K, VO>) other;
            otherTable = t;
        } else if (other instanceof InMemoryKTable<?, ?>) {
            @SuppressWarnings("unchecked")
            InMemoryKTable<K, VO> t = (InMemoryKTable<K, VO>) other;
            otherState = t.getState();
        } else {
            throw new UnsupportedOperationException("Can only join with RedisKTable or InMemoryKTable");
        }

        try {
            Map<K, VR> temp = new HashMap<>();
            Class<VR> inferred = null;
            Map<K, V> state = getState();
            for (Map.Entry<K, V> entry : state.entrySet()) {
                VO otherValue = otherTable != null ? otherTable.get(entry.getKey()) : otherState.get(entry.getKey());
                VR joinedValue = joiner.apply(entry.getValue(), otherValue);
                temp.put(entry.getKey(), joinedValue);
                if (inferred == null && joinedValue != null) {
                    @SuppressWarnings("unchecked")
                    Class<VR> c = (Class<VR>) joinedValue.getClass();
                    inferred = c;
                }
            }
            @SuppressWarnings("unchecked")
            Class<VR> valueCls = inferred != null ? inferred : (Class<VR>) Object.class;
            return newDerivedChild("leftJoin", keyClass, valueCls, temp);
        } catch (Exception e) {
            String otherName = otherTable != null ? otherTable.tableName : "in-memory table";
            log.error("Failed to left join tables {} and {}", tableName, otherName, e);
            throw new RuntimeException("Left join failed", e);
        }
    }

    @Override
    public DataStream<KeyValue<K, V>> toStream() {
        Map<K, V> snapshot = getState();
        List<KeyValue<K, V>> out = new ArrayList<>(snapshot.size());
        snapshot.forEach((k, v) -> out.add(KeyValue.of(k, v)));
        return StreamExecutionEnvironment.getExecutionEnvironment().fromCollection(out);
    }

    @Override
    public <KR> KGroupedTable<KR, V> groupBy(Function<KeyValue<K, V>, KR> keySelector) {
        return RedisKGroupedTable.from(this, keySelector);
    }

    // Serialization helpers
    private String serializeKey(K key) throws JsonProcessingException {
        return objectMapper.writeValueAsString(key);
    }

    private String serializeValue(V value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    private K deserializeKey(String keyStr) throws IOException {
        return objectMapper.readValue(keyStr, keyClass);
    }

    private V deserializeValue(String valueStr) throws IOException {
        return objectMapper.readValue(valueStr, valueClass);
    }

    @Override
    public String toString() {
        return "RedisKTable{" +
                "tableName='" + tableName + '\'' +
                ", size=" + size() +
                '}';
    }

    public String getTableName() {
        return tableName;
    }

    RedissonClient getRedissonClient() {
        return redissonClient;
    }
}
