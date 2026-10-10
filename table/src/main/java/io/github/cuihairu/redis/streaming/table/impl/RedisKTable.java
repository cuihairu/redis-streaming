package io.github.cuihairu.redis.streaming.table.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
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
    private MessageProducer changelogProducer;
    private volatile boolean changelogEnabled;
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
                appendChangelog("DEL", keyStr, null);
            } else {
                String valueStr = serializeValue(value);
                map.put(keyStr, valueStr);
                log.debug("Put key-value to table {}: {} = {}", tableName, keyStr, valueStr);
                appendChangelog("PUT", keyStr, valueStr);
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
        if (changelogEnabled) {
            // The Redis engine launches pipelines via the environment (executeAsync), and a
            // DataStream built on a hidden environment could never be started — so changelog
            // mode requires the caller's environment; the static-snapshot path stays no-arg.
            throw new IllegalStateException(
                    "Changelog is enabled for table " + tableName
                            + "; use toStream(RedisStreamExecutionEnvironment) and executeAsync() on that environment");
        }
        Map<K, V> snapshot = getState();
        List<KeyValue<K, V>> out = new ArrayList<>(snapshot.size());
        snapshot.forEach((k, v) -> out.add(KeyValue.of(k, v)));
        return StreamExecutionEnvironment.getExecutionEnvironment().fromCollection(out);
    }

    /**
     * Enable the changelog: subsequent {@link #put} calls additionally emit
     * {@code PUT}/{@code DEL} events to an MQ topic, and {@link #toStream(RedisStreamExecutionEnvironment)}
     * switches from a static snapshot to the continuous event stream (no-arg {@link #toStream()}
     * then throws — the stream needs the caller's environment to launch).
     *
     * <p>Disabled by default — every put would otherwise pay an extra MQ append, which
     * is the wrong trade for lookup/config tables that never stream. Idempotent; the
     * returned producer is shared by all puts on this instance.</p>
     *
     * @return this table, for chaining
     */
    public synchronized RedisKTable<K, V> withChangelog() {
        if (!changelogEnabled) {
            this.changelogProducer = new MessageQueueFactory(redissonClient, new MqOptions()).createProducer();
            this.changelogEnabled = true;
            log.info("Changelog enabled for table {} (topic {})", tableName, changelogTopic());
        }
        return this;
    }

    /**
     * Test hook: enable the changelog with an explicit producer instead of the default
     * MQ-factory one, so emit paths can be verified without a Redis server.
     */
    synchronized RedisKTable<K, V> withChangelog(MessageProducer producer) {
        if (!changelogEnabled) {
            this.changelogProducer = java.util.Objects.requireNonNull(producer, "producer");
            this.changelogEnabled = true;
        }
        return this;
    }

    /** @return whether {@link #withChangelog()} has been called on this instance. */
    public boolean isChangelogEnabled() {
        return changelogEnabled;
    }

    /** @return the MQ topic this table's changelog events are emitted to. */
    public String changelogTopic() {
        return "table-changelog:" + tableName;
    }

    /**
     * Default changelog consumer group: all {@code toStream()} consumers of this table
     * share it, so multiple concurrent streams split events between them (load
     * balancing). Pass an explicit group to {@link #toStream(String)} when several
     * independent consumers must each receive the full history.
     */
    public String defaultChangelogGroup() {
        return "table-changelog-group:" + tableName;
    }

    /**
     * Changelog mode with the default consumer group: attach the continuous change-event
     * stream to the given environment (launch it with {@code env.executeAsync()}). The
     * group is created at 0-0, so the first consumer replays the full event history =
     * reconstructs table state (Flink KTable.toStream semantics).
     *
     * <p>All {@code toStream(env)} consumers of this table share this group (load
     * balancing); use {@link #toStream(RedisStreamExecutionEnvironment, String)} when
     * several independent consumers must each receive the full history.</p>
     *
     * @param env the environment to attach the pipeline to; must not be null
     * @return the continuous changelog event stream
     */
    public DataStream<KeyValue<K, V>> toStream(RedisStreamExecutionEnvironment env) {
        return toStream(env, defaultChangelogGroup());
    }

    /**
     * Changelog mode with an explicit consumer group: each distinct group receives the
     * full event history (broadcast across groups, unicast within). Launch the returned
     * pipeline via {@code env.executeAsync()}.
     *
     * @param env the environment to attach the pipeline to; must not be null
     * @param consumerGroup consumer group name; must not be blank
     * @return the continuous changelog event stream
     */
    public DataStream<KeyValue<K, V>> toStream(RedisStreamExecutionEnvironment env, String consumerGroup) {
        java.util.Objects.requireNonNull(env, "env");
        java.util.Objects.requireNonNull(consumerGroup, "consumerGroup");
        if (consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup must not be blank");
        }
        if (!changelogEnabled) {
            throw new IllegalStateException(
                    "Changelog is not enabled for table " + tableName + "; call withChangelog() first");
        }
        return env.fromMqTopic(changelogTopic(), consumerGroup).map(this::parseChangelogMessage);
    }

    /**
     * Best-effort changelog append: the primary store is already updated, so a failed
     * event emission degrades stream consumers (they keep the last known value) but
     * never breaks the put itself — same trade as the control plane's audit stream.
     */
    private void appendChangelog(String op, String keyJson, String valueJson) {
        if (!changelogEnabled) {
            return;
        }
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("op", op);
            if (keyJson != null) {
                event.put("k", keyJson);
            }
            if (valueJson != null) {
                event.put("v", valueJson);
            }
            String payload = objectMapper.writeValueAsString(event);
            changelogProducer.send(changelogTopic(), tableName, payload)
                    .whenComplete((id, err) -> {
                        if (err != null) {
                            log.warn("Changelog emit failed for table {} (op {})", tableName, op, err);
                        }
                    });
        } catch (Exception e) {
            log.warn("Changelog emit failed for table {} (op {})", tableName, op, e);
        }
    }

    @SuppressWarnings("unchecked")
    /** Test hook: parse a changelog event without going through the MQ layer. */
    KeyValue<K, V> parseChangelogForTest(Message message) {
        return parseChangelogMessage(message);
    }

    private KeyValue<K, V> parseChangelogMessage(Message message) {
        try {
            String payload = String.valueOf(message.getPayload());
            JsonNode node = objectMapper.readTree(payload);
            String op = node.path("op").asText();
            K key = node.hasNonNull("k") ? (K) deserializeKey(node.get("k").asText()) : null;
            if ("PUT".equals(op)) {
                V value = (V) deserializeValue(node.get("v").asText());
                return KeyValue.of(key, value);
            }
            if ("DEL".equals(op)) {
                return KeyValue.of(key, null);
            }
            throw new IllegalStateException("Unknown changelog op '" + op + "' on table " + tableName);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse changelog event for table " + tableName, e);
        }
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
