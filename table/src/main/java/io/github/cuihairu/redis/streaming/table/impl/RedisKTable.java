package io.github.cuihairu.redis.streaming.table.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.table.KGroupedTable;
import io.github.cuihairu.redis.streaming.table.KTable;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Redis-backed implementation of KTable for production use.
 *
 * This implementation persists the table state in Redis Hash,
 * providing durability and distributed access to the table.
 *
 * @param <K> The type of the key
 * @param <V> The type of the value
 */
@Slf4j
public class RedisKTable<K, V> implements KTable<K, V> {

    private static final long serialVersionUID = 1L;

    private final RedissonClient redissonClient;
    private final String tableName;
    private final ObjectMapper objectMapper;
    private final Class<K> keyClass;
    private final Class<V> valueClass;

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
     * Get the Redis Hash for this table
     */
    private RMap<String, String> getMap() {
        return redissonClient.getMap(tableName);
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

            for (Map.Entry<String, String> entry : map.entrySet()) {
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
     * Clear all entries from the table
     */
    public void clear() {
        getMap().clear();
        log.info("Cleared table: {}", tableName);
    }

    /**
     * Delete the entire table from Redis
     */
    public void delete() {
        getMap().delete();
        log.info("Deleted table: {}", tableName);
    }

    @Override
    public <VR> KTable<K, VR> mapValues(Function<V, VR> mapper) {
        String newTableName = tableName + ":mapValues:" + System.currentTimeMillis();
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
            RedisKTable<K, VR> result = new RedisKTable<>(redissonClient, newTableName, keyClass, valueCls);
            for (Map.Entry<K, VR> e : temp.entrySet()) {
                result.put(e.getKey(), e.getValue());
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to map values for table {}", tableName, e);
            throw new RuntimeException("Map values failed", e);
        }
    }

    @Override
    public <VR> KTable<K, VR> mapValues(BiFunction<K, V, VR> mapper) {
        String newTableName = tableName + ":mapValues:" + System.currentTimeMillis();
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
            RedisKTable<K, VR> result = new RedisKTable<>(redissonClient, newTableName, keyClass, valueCls);
            for (Map.Entry<K, VR> e : temp.entrySet()) {
                result.put(e.getKey(), e.getValue());
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to map values for table {}", tableName, e);
            throw new RuntimeException("Map values failed", e);
        }
    }

    @Override
    public KTable<K, V> filter(BiFunction<K, V, Boolean> predicate) {
        String newTableName = tableName + ":filter:" + System.currentTimeMillis();
        RedisKTable<K, V> result = new RedisKTable<>(
            redissonClient, newTableName, keyClass, valueClass
        );

        try {
            Map<K, V> state = getState();
            for (Map.Entry<K, V> entry : state.entrySet()) {
                if (predicate.apply(entry.getKey(), entry.getValue())) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception e) {
            log.error("Failed to filter table {}", tableName, e);
            throw new RuntimeException("Filter failed", e);
        }

        return result;
    }

    @Override
    public <VO, VR> KTable<K, VR> join(KTable<K, VO> other, BiFunction<V, VO, VR> joiner) {
        if (!(other instanceof RedisKTable)) {
            throw new UnsupportedOperationException("Can only join with RedisKTable");
        }

        RedisKTable<K, VO> otherTable = (RedisKTable<K, VO>) other;
        String newTableName = tableName + ":join:" + System.currentTimeMillis();

        try {
            Map<K, VR> temp = new HashMap<>();
            Class<VR> inferred = null;
            Map<K, V> state = getState();
            for (Map.Entry<K, V> entry : state.entrySet()) {
                VO otherValue = otherTable.get(entry.getKey());
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
            RedisKTable<K, VR> result = new RedisKTable<>(redissonClient, newTableName, keyClass, valueCls);
            for (Map.Entry<K, VR> e : temp.entrySet()) {
                result.put(e.getKey(), e.getValue());
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to join tables {} and {}", tableName, otherTable.tableName, e);
            throw new RuntimeException("Join failed", e);
        }
    }

    @Override
    public <VO, VR> KTable<K, VR> leftJoin(KTable<K, VO> other, BiFunction<V, VO, VR> joiner) {
        if (!(other instanceof RedisKTable)) {
            throw new UnsupportedOperationException("Can only join with RedisKTable");
        }

        RedisKTable<K, VO> otherTable = (RedisKTable<K, VO>) other;
        String newTableName = tableName + ":leftJoin:" + System.currentTimeMillis();

        try {
            Map<K, VR> temp = new HashMap<>();
            Class<VR> inferred = null;
            Map<K, V> state = getState();
            for (Map.Entry<K, V> entry : state.entrySet()) {
                VO otherValue = otherTable.get(entry.getKey());
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
            RedisKTable<K, VR> result = new RedisKTable<>(redissonClient, newTableName, keyClass, valueCls);
            for (Map.Entry<K, VR> e : temp.entrySet()) {
                result.put(e.getKey(), e.getValue());
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to left join tables {} and {}", tableName, otherTable.tableName, e);
            throw new RuntimeException("Left join failed", e);
        }
    }

    @Override
    public DataStream<KeyValue<K, V>> toStream() {
        throw new UnsupportedOperationException("toStream not implemented for RedisKTable");
    }

    @Override
    public <KR> KGroupedTable<KR, V> groupBy(Function<KeyValue<K, V>, KR> keySelector) {
        throw new UnsupportedOperationException("groupBy not implemented for RedisKTable");
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
}
