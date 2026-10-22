# Config Module

Module: `config/` (package root `io.github.cuihairu.redis.streaming.config`)

## Overview

A Redis-based distributed configuration center: publishing, fetching, deleting, listening, versioned history, and history trimming. Publishing goes through an atomic Lua script (with a Java batch-write fallback on failure), change notification uses "synchronous local dispatch + Redis Pub/Sub", and a background resync poll compensates for lost notifications.

## Core Interfaces

### ConfigService

The single interface of the configuration service; both the publisher and subscriber perspectives are declared here (the former `ConfigManager`/`ConfigCenter` interfaces were merged in, consolidated 2026-10-12).

```java
public interface ConfigService {
    String getConfig(String dataId, String group);
    default String getConfig(String dataId, String group, String defaultValue);
    default boolean hasConfig(String dataId, String group);
    boolean publishConfig(String dataId, String group, String content);
    boolean publishConfig(String dataId, String group, String content, String description);
    boolean removeConfig(String dataId, String group);
    void addListener(String dataId, String group, ConfigChangeListener listener);
    void removeListener(String dataId, String group, ConfigChangeListener listener);
    List<ConfigHistory> getConfigHistory(String dataId, String group, int size);
    default int trimHistoryBySize(String dataId, String group, int maxSize);
    default int trimHistoryByAge(String dataId, String group, java.time.Duration maxAge);
    void start();
    void stop();
    default boolean isRunning();
}
```

Behavior notes (implementation class `impl.RedisConfigService`):

- Read/write methods throw `IllegalStateException` before `start()`
- `publishConfig` generates a new version number on every call (`millis-serial`), and writes the old content to history (when `historySize>0`)
- `removeConfig` returns false when the configuration does not exist; a successful delete broadcasts an event with null content
- `addListener` fires once immediately with the current content/version when the configuration already exists
- In `getConfigHistory(size)`, `size<=0` returns an empty list
- The two `trimHistoryByXxx` methods return the number of deleted records

### ConfigChangeListener

```java
@FunctionalInterface
public interface ConfigChangeListener {
    void onChange(String dataId, String group, String content, String version);
}
```

### ConfigChangeEvent (Pub/Sub payload)

```java
public class ConfigChangeEvent {
    private String dataId;
    private String group;
    private String content;     // null for delete events
    private String version;     // String; null for delete events
    private long timestamp;
    private String publisherId; // identifies the publishing instance, used to skip the publisher's own Pub/Sub echo
}
```

### Data Model

```java
// history record
public class ConfigHistory {
    private String dataId;
    private String group;
    private String content;
    private String version;         // String
    private String description;     // history stores the operation type: UPDATED / DELETED
    private LocalDateTime changeTime;
    private String operator;
}

// in-memory view of the configuration Hash (parsed by ConfigEntryCodec, used inside RedisConfigService)
public class ConfigEntry {
    private String dataId;
    private String group;
    private String content;
    private String version;
    private String description;
    private LocalDateTime updateTime;
    private LocalDateTime createTime;
}
```

## Usage

### 1. Basic Use

```java
ConfigService configService = new RedisConfigService(redissonClient);
configService.start();

configService.publishConfig("app.properties", "DEFAULT", "timeout=5000");
String config = configService.getConfig("app.properties", "DEFAULT");

configService.addListener("app.properties", "DEFAULT",
    (dataId, group, content, version) ->
        System.out.println("config updated: " + content + " version=" + version));

configService.removeConfig("app.properties", "DEFAULT");
configService.stop();
```

The constructor also accepts configuration: `new RedisConfigService(redissonClient, new ConfigServiceConfig("myapp", true))`.

### 2. Existence Check

```java
configService.start();
boolean exists = configService.hasConfig("app.properties", "dev");
```

### 3. History Query

```java
List<ConfigHistory> history = configService.getConfigHistory("app.properties", "DEFAULT", 10);
for (ConfigHistory h : history) {
    System.out.println("version: " + h.getVersion());
    System.out.println("time: " + h.getChangeTime());
    System.out.println("operation: " + h.getDescription());
    System.out.println("content: " + h.getContent());
}
```

### 4. History Trimming

```java
configService.trimHistoryBySize("app.properties", "DEFAULT", 100);              // keep the latest 100 entries
configService.trimHistoryByAge("app.properties", "DEFAULT", Duration.ofDays(30)); // delete entries older than 30 days
```

## Configuration

### ConfigServiceConfig

| Parameter | Type | Default | Notes |
|------|------|--------|------|
| keyPrefix | String | `redis_streaming` | Redis key prefix |
| enableKeyPrefix | boolean | true | Whether the key prefix is enabled |
| historySize | int | 10 | History entries kept per configuration; 0 disables history, negatives are clamped to 0 |
| resyncIntervalMs | long | 30000 | Resync poll interval (ms); 0 disables, negatives clamped to 0 |
| contentCipher | ContentCipher | null | At-rest content cipher; null means everything is plaintext (default) |
| securePrefix | String | `secure.` | Only dataIds matching this prefix get encrypted (matched on the dataId prefix) |

Key generation methods (`ConfigServiceConfig`; `:` inside group/dataId is replaced with `_`):

| Method | Key template |
|------|--------|
| `getConfigKey(group, dataId)` | `{prefix}:config:{group}:{dataId}` |
| `getConfigHistoryKey(group, dataId)` | `{prefix}:config_history:{group}:{dataId}` |
| `getConfigSubscribersKey(group, dataId)` | `{prefix}:config_subscribers:{group}:{dataId}` |
| `getConfigChangeChannelKey(group, dataId)` | `{prefix}:config_change:{group}:{dataId}` |

### Spring Boot Properties

Starter (`RedisStreamingProperties`, prefix `redis-streaming`):

| Property | Type | Default | Notes |
|------|------|--------|------|
| redis-streaming.config.enabled | boolean | true | Whether the ConfigService bean is wired |
| redis-streaming.config.key-prefix | String | `redis_streaming` | Redis key prefix |
| redis-streaming.config.enable-key-prefix | boolean | true | Whether the key prefix is enabled |
| redis-streaming.config.history-size | int | 10 | Number of history records |
| redis-streaming.config.default-group | String | DEFAULT_GROUP | Used only in startup logs; not part of the wiring logic |
| redis-streaming.config.secure-key-prefix | String | `secure.` | Only dataIds matching this prefix get encrypted; the cipher comes from an optional `ContentCipher` bean in the container |

The starter does not map `resyncIntervalMs`; construct and pass a `ConfigServiceConfig` yourself when needed.

Also note: the starter declares a `@io.github.cuihairu.redis.streaming.starter.annotation.ConfigChangeListener` annotation (`dataId`/`group`/`autoRefresh`), but no processor in the main source reads that annotation, so annotated methods are never invoked (not seen in any implementation); use the programmatic `addListener` for configuration listening.

## Design Notes

### Redis Data Layout

1. Configuration storage: Hash `{prefix}:config:{group}:{dataId}` with fields `content`, `version`, `description`, `createTime`, `updateTime`
2. History: List `{prefix}:config_history:{group}:{dataId}` whose elements are JSON (dataId/group/content/version/operation/changeTime/operator), written with LPUSH and trimmed to `historySize` with LTRIM
3. Subscriber registry: Set `{prefix}:config_subscribers:{group}:{dataId}` whose members are instance clientIds, removed on `stop()`
4. Pub/Sub: channel `{prefix}:config_change:{group}:{dataId}` carrying `ConfigChangeEvent` messages

### Configuration Change Flow

1. `publishConfig` atomically writes the configuration Hash via Lua, LPUSHes the old content into history, and trims it
2. The version number is produced by `generateVersion()`: `millis-serial`; the serial increments within the same millisecond, and on clock rollback it continues the latest millisecond's serial
3. The publisher first dispatches the change synchronously to listeners in its own JVM (visible before `publish` returns)
4. Then it notifies other instances via Pub/Sub; events carry a `publisherId`, and the publishing instance's own echo is skipped
5. Listener dispatch is idempotent on `(content, version)`, delivered exactly once even when racing the resync poll
6. After `start()` (when `resyncIntervalMs>0`) a background thread re-reads all subscribed configurations at a fixed interval and re-dispatches when content differs from the last dispatch, compensating for Pub/Sub notifications lost during connection flaps; exceptions in the poll are caught and never terminate the schedule

### Content Encryption (at rest)

Configuration content is stored in Redis as plaintext by default. To protect sensitive configuration, implement `ContentCipher` (`encrypt` / `decrypt`, plain strings; key management is up to the implementation — usually environment variables or JVM flags, never stored in Redis alongside the protected data) and install it:

- Programmatically: `new RedisConfigService(client, config, cipher)`, or `config.setContentCipher(cipher)`
- Spring Boot: declare a `ContentCipher` bean and the starter wires it in automatically; without one, plaintext remains (default no-op)

**Scope**: matched by `securePrefix` (default `secure.`) against the dataId prefix. For matching configurations, both the Hash's `content` field and the history's `content` are stored wrapped as `enc:v1:` ciphertext; every read path (`getConfig`, `getConfigHistory`, initial listener dispatch, resync poll) transparently decrypts back to plaintext, and Pub/Sub events likewise carry plaintext. **Protection covers Redis persistence (at rest) only**: in-process and over the Pub/Sub channel it is still plaintext.

**Compatibility**:

- Without a cipher installed, bytes are unchanged (default no-op)
- dataIds not matching the prefix are unaffected
- Existing plaintext entries are not re-encrypted automatically: content without the `enc:v1:` prefix is returned as plaintext until the next publish converts it — installing a cipher requires no data migration
- Ciphertext already in Redis read by a cipher-less instance: returns the raw ciphertext with a warning, never silently treated as plaintext
- Content already carrying `enc:v1:` is not wrapped again on republish
- Encryption/decryption failure: the publish side throws before writing (no plaintext fallback — silently storing plaintext equals not encrypting); the read side throws

**Key rotation**: this module ships no re-encryption tool. After changing keys, republish sensitive configurations one by one; during the transition window old ciphertext still matches `enc:v1:` but cannot be decrypted, and reads throw — rotation must therefore complete within a maintenance window.

### History Trimming Semantics

- Publish/delete paths: trimmed to the latest N entries when `historySize>0`; with `historySize=0` no history is written at all
- `trimHistoryBySize(maxSize<=0)`: clears that configuration's history list
- `trimHistoryByAge`: walks from the tail (oldest) comparing `changeTime` against the cutoff and trims the expired range

## Caveats

1. Start before use; read/write methods throw `IllegalStateException` before `start()`
2. `historySize=0` disables history entirely; neither publish nor delete writes history records
3. Listener callback threads: local dispatch runs on the publisher's calling thread, Pub/Sub dispatch runs on a Redisson message thread; listeners should return quickly and hand heavy work to a business thread pool
4. Duplicate callbacks are deduplicated at the implementation layer via `publisherId` and `(content, version)` idempotency; across instances each instance still receives one callback
5. Listener registration/unregistration/stop are serialized by the same lock; concurrency tests (`ConfigServiceConcurrencyTest`, `ConfigServiceConcurrentAddListenerRaceTest`, etc.) cover this path

## Related Documentation

- [Registry module](Registry.md) - service registration and discovery
- config module README - module-internal notes
- [Spring Boot Starter](Spring-Boot-Starter.md) - Spring Boot integration
