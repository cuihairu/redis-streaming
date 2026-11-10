package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.redisson.api.RKeys;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keyed-state half of {@link RedisRuntimeCheckpointManager}: reads the job's state-key
 * index into {@link RedisRuntimeCheckpointManager.RedisStateValue} snapshots and restores
 * them via stage-then-RENAME swaps that never leave live state half-written.
 */
final class RedisRuntimeKeyedStateSnapshotter {

    private static final Logger log = LoggerFactory.getLogger(RedisRuntimeKeyedStateSnapshotter.class);

    private final RedisRuntimeCheckpointManager manager;

    RedisRuntimeKeyedStateSnapshotter(RedisRuntimeCheckpointManager manager) {
        this.manager = manager;
    }

    Map<String, RedisRuntimeCheckpointManager.RedisStateValue> snapshotState() {
        Map<String, RedisRuntimeCheckpointManager.RedisStateValue> out = new HashMap<>();
        String indexKey = manager.config.getStateKeyPrefix() + ":" + manager.config.getJobName() + ":stateKeys";
        RSet<String> index = manager.redissonClient.getSet(indexKey, StringCodec.INSTANCE);
        List<String> keys = new ArrayList<>();
        try {
            keys.addAll(index.readAll());
        } catch (Exception ex) {
            log.warn("Checkpoint state operation failed", ex);
        }

        RKeys rkeys = manager.redissonClient.getKeys();
        for (String k : keys) {
            if (k == null || k.isBlank()) continue;
            try {
                if (rkeys.countExists(k) <= 0) {
                    try {
                        index.remove(k);
                    } catch (Exception ex) {
                        log.debug("Checkpoint state operation failed", ex);
                    }
                    continue;
                }
                RType type = null;
                try {
                    type = rkeys.getType(k);
                } catch (Exception ex) {
                    log.debug("Checkpoint state operation failed", ex);
                }
                if (type == RType.ZSET) {
                    RScoredSortedSet<String> set = manager.redissonClient.getScoredSortedSet(k, StringCodec.INSTANCE);
                    Map<String, Double> data = new HashMap<>();
                    for (org.redisson.client.protocol.ScoredEntry<String> e : set.entryRange(0, -1)) {
                        if (e == null || e.getValue() == null) {
                            continue;
                        }
                        data.put(e.getValue(), e.getScore());
                    }
                    if (data != null && !data.isEmpty()) {
                        out.put(k, RedisRuntimeCheckpointManager.RedisStateValue.ofZset(data));
                    } else {
                        try {
                            index.remove(k);
                        } catch (Exception ex) {
                            log.debug("Checkpoint state operation failed", ex);
                        }
                    }
                } else if (type == RType.MAP || type == null) {
                    RMap<String, String> map = manager.redissonClient.<String, String>getMap(k, StringCodec.INSTANCE);
                    Map<String, String> data = map.readAllMap();
                    if (data != null && !data.isEmpty()) {
                        out.put(k, RedisRuntimeCheckpointManager.RedisStateValue.ofMap(new HashMap<>(data)));
                    } else {
                        try {
                            index.remove(k);
                        } catch (Exception ex) {
                            log.debug("Checkpoint state operation failed", ex);
                        }
                    }
                } else if (type == RType.LIST) {
                    RList<String> list = manager.redissonClient.getList(k, StringCodec.INSTANCE);
                    List<String> data = list.readAll();
                    if (data != null && !data.isEmpty()) {
                        out.put(k, RedisRuntimeCheckpointManager.RedisStateValue.ofList(new ArrayList<>(data)));
                    } else {
                        try {
                            index.remove(k);
                        } catch (Exception ex) {
                            log.debug("Checkpoint state operation failed", ex);
                        }
                    }
                } else if (type == RType.SET) {
                    RSet<String> set = manager.redissonClient.<String>getSet(k, StringCodec.INSTANCE);
                    Set<String> data = set.readAll();
                    if (data != null && !data.isEmpty()) {
                        out.put(k, RedisRuntimeCheckpointManager.RedisStateValue.ofSet(new java.util.HashSet<>(data)));
                    } else {
                        try {
                            index.remove(k);
                        } catch (Exception ex) {
                            log.debug("Checkpoint state operation failed", ex);
                        }
                    }
                } else {
                    log.debug("Skip snapshot for unsupported state key type {}: {}", String.valueOf(type), k);
                }
            } catch (Exception e) {
                log.debug("Failed to snapshot state key {}", k, e);
            }
        }
        return out;
    }

    Map<String, String> snapshotStateSchema() {
        Map<String, String> out = new HashMap<>();
        String indexKey = manager.config.getStateKeyPrefix() + ":" + manager.config.getJobName() + ":stateKeys";
        String schemaKey = manager.config.getStateKeyPrefix() + ":" + manager.config.getJobName() + ":stateSchema";
        RSet<String> index = manager.redissonClient.getSet(indexKey, StringCodec.INSTANCE);
        List<String> keys = new ArrayList<>();
        try {
            keys.addAll(index.readAll());
        } catch (Exception ex) {
            log.warn("Checkpoint state operation failed", ex);
        }

        RMap<String, String> schema = manager.redissonClient.getMap(schemaKey, StringCodec.INSTANCE);
        for (String k : keys) {
            if (k == null || k.isBlank()) continue;
            try {
                String v = schema.get(k);
                if (v != null && !v.isBlank()) {
                    out.put(k, v);
                }
            } catch (Exception ex) {
                log.debug("Checkpoint state operation failed", ex);
            }
        }
        return out;
    }

    void restoreState(Checkpoint checkpoint) {
        Object raw = checkpoint.getStateSnapshot().getState(RedisRuntimeCheckpointManager.SNAPSHOT_KEY_STATE);
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return;
        }
        Map<String, RedisRuntimeCheckpointManager.RedisStateValue> state = new HashMap<>();
        for (Map.Entry<?, ?> e : rawMap.entrySet()) {
            if (!(e.getKey() instanceof String redisKey)) {
                continue;
            }
            Object v = e.getValue();
            if (v instanceof RedisRuntimeCheckpointManager.RedisStateValue sv) {
                state.put(redisKey, sv);
                continue;
            }
            if (v instanceof Map<?, ?> m) {
                Map<String, String> data = new HashMap<>();
                for (Map.Entry<?, ?> me : m.entrySet()) {
                    if (me.getKey() == null || me.getValue() == null) {
                        continue;
                    }
                    data.put(String.valueOf(me.getKey()), String.valueOf(me.getValue()));
                }
                state.put(redisKey, RedisRuntimeCheckpointManager.RedisStateValue.ofMap(data));
            }
        }
        if (state.isEmpty()) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> schemaSnap = checkpoint.getStateSnapshot().getState(RedisRuntimeCheckpointManager.SNAPSHOT_KEY_STATE_SCHEMA);

        String indexKey = manager.config.getStateKeyPrefix() + ":" + manager.config.getJobName() + ":stateKeys";
        String schemaKey = manager.config.getStateKeyPrefix() + ":" + manager.config.getJobName() + ":stateSchema";
        RSet<String> index = manager.redissonClient.getSet(indexKey, StringCodec.INSTANCE);
        List<String> existing = new ArrayList<>();
        try {
            existing.addAll(index.readAll());
        } catch (Exception ex) {
            log.warn("Checkpoint state operation failed", ex);
        }

        // RT-M4: restore used to be delete-then-rebuild — a failure in the middle left the
        // live state wiped and the per-key failures were swallowed, so the job continued
        // with missing (or partially rebuilt) state. Now every snapshot key is first written
        // under a staging name (nothing live is touched), then flipped into place with an
        // atomic RENAME per key; any staging or swap failure throws and the caller aborts
        // the restore instead of degrading into a stateless start.
        String stage = ":rst:" + checkpoint.getCheckpointId();
        List<String[]> stagedPairs = new ArrayList<>();   // {stagedName, finalName}
        List<String> stagedNames = new ArrayList<>();     // every staging key created
        List<String> clearedKeys = new ArrayList<>();     // snapshot keys that must end up empty
        java.util.Set<String> snapshotKeys = new java.util.HashSet<>();
        boolean stagedIndexExists = false;                // the staging index key was actually created
        boolean stagedSchemaExists = false;               // the staging schema key was actually created
        try {
            for (Map.Entry<String, RedisRuntimeCheckpointManager.RedisStateValue> e : state.entrySet()) {
                String redisKey = e.getKey();
                RedisRuntimeCheckpointManager.RedisStateValue value = e.getValue();
                if (redisKey == null || redisKey.isBlank() || value == null || value.type() == null) {
                    continue;
                }
                snapshotKeys.add(redisKey);
                if (value.type() == RedisRuntimeCheckpointManager.RedisStateType.ZSET) {
                    Map<String, Double> data = value.zset();
                    if (data == null || data.isEmpty()) {
                        clearedKeys.add(redisKey);
                        continue;
                    }
                    String stagedName = redisKey + stage;
                    RScoredSortedSet<String> set = manager.redissonClient.getScoredSortedSet(stagedName, StringCodec.INSTANCE);
                    try {
                        rkeysDeleteQuietly(stagedName); // idempotent re-staging after a failed attempt
                    } catch (Exception ex) {
                        log.debug("Checkpoint state operation failed", ex);
                    }
                    set.addAll(data);
                    stagedPairs.add(new String[]{stagedName, redisKey});
                    stagedNames.add(stagedName);
                } else if (value.type() == RedisRuntimeCheckpointManager.RedisStateType.LIST) {
                    List<String> data = value.list();
                    if (data == null || data.isEmpty()) {
                        clearedKeys.add(redisKey);
                        continue;
                    }
                    String stagedName = redisKey + stage;
                    RList<String> list = manager.redissonClient.getList(stagedName, StringCodec.INSTANCE);
                    try {
                        rkeysDeleteQuietly(stagedName);
                    } catch (Exception ex) {
                        log.debug("Checkpoint state operation failed", ex);
                    }
                    list.addAll(data);
                    stagedPairs.add(new String[]{stagedName, redisKey});
                    stagedNames.add(stagedName);
                } else if (value.type() == RedisRuntimeCheckpointManager.RedisStateType.SET) {
                    Set<String> data = value.set();
                    if (data == null || data.isEmpty()) {
                        clearedKeys.add(redisKey);
                        continue;
                    }
                    String stagedName = redisKey + stage;
                    RSet<String> set = manager.redissonClient.<String>getSet(stagedName, StringCodec.INSTANCE);
                    try {
                        rkeysDeleteQuietly(stagedName);
                    } catch (Exception ex) {
                        log.debug("Checkpoint state operation failed", ex);
                    }
                    set.addAll(data);
                    stagedPairs.add(new String[]{stagedName, redisKey});
                    stagedNames.add(stagedName);
                } else {
                    Map<String, String> data = value.map();
                    if (data == null || data.isEmpty()) {
                        clearedKeys.add(redisKey);
                        continue;
                    }
                    String stagedName = redisKey + stage;
                    RMap<String, String> map = manager.redissonClient.<String, String>getMap(stagedName, StringCodec.INSTANCE);
                    try {
                        rkeysDeleteQuietly(stagedName);
                    } catch (Exception ex) {
                        log.debug("Checkpoint state operation failed", ex);
                    }
                    map.putAll(data);
                    stagedPairs.add(new String[]{stagedName, redisKey});
                    stagedNames.add(stagedName);
                }
            }

            // stage the state-key index and the schema map the same way. An empty staging
            // write (no state keys / no schema entries) creates no Redis key, so only a
            // staging key that actually exists is renamed — RENAME of an absent key errors.
            // A snapshot with no entries means the live counterpart must end up empty:
            // it is dropped instead.
            stagedIndexExists = !stagedPairs.isEmpty();
            if (stagedIndexExists) {
                RSet<String> stagedIndex = manager.redissonClient.getSet(indexKey + stage, StringCodec.INSTANCE);
                rkeysDeleteQuietly(indexKey + stage);
                stagedIndex.addAll(stagedPairs.stream().map(p -> p[1]).toList());
                stagedNames.add(indexKey + stage);
            }
            RMap<String, String> stagedSchema = manager.redissonClient.<String, String>getMap(schemaKey + stage, StringCodec.INSTANCE);
            rkeysDeleteQuietly(schemaKey + stage);
            if (schemaSnap != null) {
                Map<String, String> filtered = new HashMap<>();
                for (String[] p : stagedPairs) {
                    String sv = schemaSnap.get(p[1]);
                    if (sv != null && !sv.isBlank()) {
                        filtered.put(p[1], sv);
                    }
                }
                if (!filtered.isEmpty()) {
                    stagedSchema.putAll(filtered);
                    stagedSchemaExists = true;
                    stagedNames.add(schemaKey + stage);
                }
            }
        } catch (Exception ex) {
            cleanupStagedKeys(stagedNames);
            throw new IllegalStateException("Failed to stage checkpoint state for restore (checkpoint "
                    + checkpoint.getCheckpointId() + "); live state is untouched", ex);
        }

        RKeys rkeys = manager.redissonClient.getKeys();
        try {
            for (String[] pair : stagedPairs) {
                rkeys.rename(pair[0], pair[1]);
            }
            if (stagedIndexExists) {
                rkeys.rename(indexKey + stage, indexKey);
            } else {
                rkeys.delete(indexKey);
            }
            if (stagedSchemaExists) {
                rkeys.rename(schemaKey + stage, schemaKey);
            } else {
                rkeys.delete(schemaKey);
            }
            // stale keys: present in the previous index but absent from this snapshot —
            // dropped only after every staged key landed
            for (String k : existing) {
                if (k == null || k.isBlank() || snapshotKeys.contains(k)) {
                    continue;
                }
                try {
                    rkeys.delete(k);
                } catch (Exception ex) {
                    log.warn("Checkpoint state operation failed", ex);
                }
            }
            for (String k : clearedKeys) {
                try {
                    rkeys.delete(k);
                } catch (Exception ex) {
                    log.warn("Checkpoint state operation failed", ex);
                }
            }
        } catch (Exception ex) {
            cleanupStagedKeys(stagedNames);
            throw new IllegalStateException("State restore swap failed (checkpoint "
                    + checkpoint.getCheckpointId() + "); refusing to continue with partially restored state", ex);
        }

        // TTL is correctness-adjacent (retention), not correctness itself: a failure here
        // is logged, not fail-stop — the restored state is already consistent at this point.
        Duration ttl = manager.config.getStateTtl();
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            for (String[] pair : stagedPairs) {
                try {
                    RedisRuntimeCheckpointManager.RedisStateType t = state.get(pair[1]).type();
                    if (t == RedisRuntimeCheckpointManager.RedisStateType.ZSET) {
                        manager.redissonClient.getScoredSortedSet(pair[1], StringCodec.INSTANCE).expire(ttl);
                    } else if (t == RedisRuntimeCheckpointManager.RedisStateType.LIST) {
                        manager.redissonClient.getList(pair[1], StringCodec.INSTANCE).expire(ttl);
                    } else if (t == RedisRuntimeCheckpointManager.RedisStateType.SET) {
                        manager.redissonClient.<String>getSet(pair[1], StringCodec.INSTANCE).expire(ttl);
                    } else {
                        manager.redissonClient.<String, String>getMap(pair[1], StringCodec.INSTANCE).expire(ttl);
                    }
                } catch (Exception ex) {
                    log.warn("Failed to apply state TTL to restored key {}", pair[1], ex);
                }
            }
        }
    }

    private void rkeysDeleteQuietly(String key) {
        try {
            manager.redissonClient.getKeys().delete(key);
        } catch (Exception ex) {
            log.debug("Checkpoint state operation failed", ex);
        }
    }

    private void cleanupStagedKeys(List<String> stagedNames) {
        for (String staged : stagedNames) {
            try {
                manager.redissonClient.getKeys().delete(staged);
            } catch (Exception ex) {
                log.debug("Failed to clean up staging key {}", staged, ex);
            }
        }
    }
}
