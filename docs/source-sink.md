# Source & Sink Modules

Module directories: `source/` and `sink/`, 7 main source classes each.

Two kinds of contracts (see [Core.md](Core.md)):
- The streaming contracts from `core`: `StreamSource` (`run(SourceContext)` + `cancel()`, with default `open()/close()` lifecycle) and `StreamSink` (`invoke(T)`, also with the default lifecycle) — driven by the in-memory runtime's `StreamExecutionEnvironment.addSource(...)` / `DataStream.addSink(...)`;
- Callback-style `AutoCloseable`: these own their polling thread / consumer loop, bypass the runtime, and are finalized with `try-with-resources` or `close()`.

## Sources (source module, 7)

| Class | Contract | Notes |
|---|---|---|
| `source.collection.CollectionSource` | `StreamSource` | Emits a `Collection` in one shot (`collect`); `cancel()` stops it |
| `source.generator.GeneratorSource` | `StreamSource` | Generates `count` elements from a `Supplier`, with optional `delayMillis` spacing |
| `source.file.FileSource` | `StreamSource` | Reads a text file line by line, one `String` per line |
| `source.http.HttpApiSource` | `AutoCloseable` (callback-style) | Polls a REST API on a timer; `fetch()` for a single object / `fetchList()` for a list |
| `source.kafka.KafkaSource` | `AutoCloseable` (callback-style) | Kafka consumer; `poll(timeout)` / `consume(handler)` |
| `source.redis.RedisListSource` | `AutoCloseable` (callback-style) | Pops from the head of a Redis **List** (`RList.remove(0)`, i.e. LPOP semantics) |
| `source.redis.RedisStreamSource` | `StreamSource` | Consumes a Redis **Stream** via XREADGROUP (paired with `RedisStreamSink`) |

### RedisStreamSource

Consumes a Redis Stream via XREADGROUP; entries are expected to carry a single-field JSON payload (default field name `value`, constant `DEFAULT_VALUE_FIELD`, symmetric with `RedisStreamSink`). `String` payloads pass through as-is; other types are Jackson-deserialized into `valueClass`.

```java
// 5-arg constructor: valueField="value", batchCount=32, pollTimeoutMs=200ms, maxIdlePolls=3
RedisStreamSource<String> source =
        new RedisStreamSource<>(redissonClient, "events", "my-group", "consumer-1", String.class);
// full constructor
new RedisStreamSource<>(redissonClient, "events", "my-group", "consumer-1",
        "payload", Order.class, 64, 500L, 10);   // valueField, valueClass, batchCount, pollTimeoutMs, maxIdlePolls
```

Behavior:
- `run()` creates the consumer group on startup (when absent), using an explicit `0-0` start id rather than `StreamMessageId.MIN`'s `-` (the latter requires Redis ≥ 7.0; see CHANGELOG); if the group already exists it is skipped.
- `run()` performs a bounded drain: it returns after `maxIdlePolls` consecutive empty reads, fitting the pull-based engine (the in-memory runtime's `addSource` drives it exactly this way, executing immediately and buffering records).
- Each entry is delivered via `collectWithTimestamp` (timestamp taken from the ms part of the entry id) and immediately `XACK`ed; entries missing the `value` field log a warning, are skipped, and are `XACK`ed all the same.
- Argument validation: `batchCount >= 1`, `maxIdlePolls >= 1`, otherwise `IllegalArgumentException`.
- Getters: `getStreamName()`, `getConsumerGroup()`.

### Remaining Sources at a Glance

- `HttpApiSource<T>`: constructors `(apiUrl, valueClass)` or `(apiUrl, valueClass, objectMapper, pollInterval, headers)`; defaults `pollInterval=10s`, connect timeout 5s, read timeout 10s, and an automatic `Accept: application/json` header. `poll(handler)` polls on the `fetch()` cycle, `pollList(handler)` calls back per item from `fetchList()`; on request failure `fetch()` returns null / `fetchList()` returns an empty list (nothing is thrown). Getters: `getApiUrl()`, `getPollInterval()`, `isRunning()`.
- `KafkaSource<T>`: constructor `(bootstrapServers, groupId, topic, valueClass)`, or pass `Properties` / a ready `Consumer`. Default consumer settings: `auto.offset.reset=earliest`, `enable.auto.commit=true` (interval 1000ms), `max.poll.records=500`, String deserializers. Methods: `poll(Duration)`, `consume(handler)`, `consumeAsync(handler)` (returns a daemon thread), `stop()`, `seekToBeginning()`, `seekToEnd()` (a short poll runs before seeking to obtain the partition assignment), `commitSync()`, `getTopic()`, `isRunning()`, `close()`.
- `RedisListSource<T>`: constructors `(redissonClient, listName, valueClass)` or plus `objectMapper`. Methods: `readOne()` (LPOP), `readBatch(count)`, `readAll()`, `consume(handler)` (continuous pulling, sleeps 100ms on an empty list), `poll(handler, pollInterval)`, `pollBatch(handler, batchSize, pollInterval)` (batchSize must be > 0), `stop()`, `getSize()`, `isEmpty()`, `getListName()`, `isRunning()`, `close()`. Deserialization failures return null (the item is dropped and an error is logged).
- `CollectionSource<T>` / `GeneratorSource<T>` / `FileSource` (all three are `StreamSource`):
  - `GeneratorSource`: constructors `(generator)` (unbounded, until `cancel()`), `(generator, count)`, `(generator, count, delayMillis)`; static factories `GeneratorSource.sequence(count)` and `sequence(start, count)` produce `Long` sequences.
  - `FileSource`: constructor `(String filePath)` or `(Path)`; file-read failures are wrapped in a `RuntimeException`.

## Sinks (sink module, 7)

| Class | Contract | Notes |
|---|---|---|
| `sink.print.PrintSink` | `StreamSink` | Writes to a `PrintStream` (default `System.out`), optional prefix/timestamp |
| `sink.collection.CollectionSink` | `StreamSink` | Collects into a `Collection` (default `ArrayList`; common in tests) |
| `sink.file.FileSink` | `StreamSink` | Writes to a file record by record, one line per element, flushed after each write; `close()` releases the handle |
| `sink.kafka.KafkaSink` | `AutoCloseable` (not a `StreamSink`) | Kafka producer, sync/async writes, optional `KeyExtractor` |
| `sink.redis.RedisStreamSink` | `StreamSink` | Real **XADD** to a Redis Stream |
| `sink.redis.RedisListSink` | `StreamSink` | `RList.add` (RPUSH) to a Redis List |
| `sink.redis.RedisHashSink` | Plain class (neither `StreamSink` nor `AutoCloseable`) | Writes a Redis Hash (HSET) |

### RedisStreamSink / RedisListSink

```java
import io.github.cuihairu.redis.streaming.sink.redis.RedisStreamSink;
import io.github.cuihairu.redis.streaming.sink.redis.RedisListSink;

// XADD: one stream entry per record, payload stored under field "value" (String stored as-is, others JSON-serialized)
stream.addSink(new RedisStreamSink<>(redissonClient, "out-stream"));
// custom field name + ObjectMapper
stream.addSink(new RedisStreamSink<>(redissonClient, "out-stream", "payload", mapper));
// RPUSH: Redis List semantics
stream.addSink(new RedisListSink<>(redissonClient, "out-list"));
```

- `RedisStreamSink<T>`: field name defaults to `"value"` (`DEFAULT_VALUE_FIELD`); methods `write(T)` (throws `RuntimeException` on failure), `writeAsync(T)`, `writeBatch(Iterable<T>)` (returns the number of successes), `getSize()` (XLEN), `clear()` (deletes the stream), `getStreamName()`, `getValueField()`. Any null constructor argument throws NPE.
- `RedisListSink<T>`: `write` uses `RList.add` (appends to the list tail, RPUSH); the remaining methods mirror `RedisStreamSink`, plus `deleteList()` and `getListName()`.
- Historical change (see CHANGELOG "Unreleased"): the old `RedisStreamSink` was named "Stream" but actually wrote a List; it now performs a real XADD, and the original List behavior moved to `RedisListSink`.

### RedisHashSink<K, V>

```java
RedisHashSink<String, Order> hash = new RedisHashSink<>(redissonClient, "orders-by-id", Order.class);
hash.write("id-1", order);                       // HSET (both key and value JSON-serialized; strings stored as-is)
hash.writeBatch(Map.of("id-2", order2));         // batched HSET
Order removed = hash.delete("id-1");             // HDEL, returns the old value (deserialized per valueClass)
```

- Constructors: `(redissonClient, hashName)`, `(redissonClient, hashName, valueClass)` (typed variant: `delete` deserializes to that type), `(redissonClient, hashName, objectMapper, ttl)` (expires the whole hash with `EXPIRE` after each write).
- Methods: `write(key, value)`, `writeAsync`, `writeBatch(Map)`, `delete(key)` (returns null on miss), `getHashSize()`, `clear()`, `deleteHash()`, `getHashName()`.
- `writeWithFieldTTL(key, value, fieldTtl)`: field-level TTL requires Redis 7.4+ HPEXPIRE, which the current Redisson version does not support — the method only writes the value and logs a warning, and only when a `ttl` was configured at construction does it apply `expire(fieldTtl)` to the whole hash (overriding the constructor TTL); with no `ttl` configured, apart from the warning there is no TTL effect.

### KafkaSink / PrintSink / CollectionSink / FileSink

- `KafkaSink<T>`: constructors `(bootstrapServers, topic)`, `(bootstrapServers, topic, objectMapper, keyExtractor)`, or `(Properties, topic, objectMapper, keyExtractor)`. Default producer settings: `acks=1`, `retries=3`, `linger.ms=10`, `batch.size=16384`, `compression.type=snappy`, String serializers. Methods: `write(T)` (synchronous, returns `RecordMetadata`), `writeAsync(T)`, `writeToPartition(T, int)`, `flush()`, `getTopic()`, `close()`. `KeyExtractor<T>` is the functional interface `String extractKey(T)`; `write(null)` throws NPE.
- `PrintSink<T>`: constructors `()`, `(prefix)`, `(PrintStream)`, `(PrintStream, prefix, withTimestamp)`; output format `[timestamp] prefix: element`; static factories `PrintSink.withTimestamp()` and `withPrefixAndTimestamp(prefix)`.
- `CollectionSink<T>`: constructors `()` (new `ArrayList`) or `(Collection<T>)`; methods `getCollection()`, `getList()` (returns null when not a List), `size()`, `clear()`.
- `FileSink<T>`: constructors `(filePath)` (overwrite), `(Path)`, `(filePath, append)`; `invoke` writes `value.toString()` + a newline per record and flushes; `close()` releases the handle (streaming usage holding the writer should call it explicitly).

## Relationship with the Redis Runtime

The Redis runtime engine (`runtime/redis/sink`) has its own set of built-in sinks — `RedisIdempotentListSink`, `RedisCheckpointedIdempotentListSink`, `RedisAtomicCheckpointListSink`, `RedisOutboxSink`, etc. — used for exactly-once demonstrations; they do not depend on this module or vice versa.

## References
- [Core.md](Core.md) · [runtime.md](runtime.md) · [MQ.md](MQ.md)
