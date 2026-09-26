package io.github.cuihairu.redis.streaming.checkpoint.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import io.github.cuihairu.redis.streaming.checkpoint.storage.CheckpointStorage;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Redis-based implementation of CheckpointStorage.
 */
public class RedisCheckpointStorage implements CheckpointStorage {

    private final RedissonClient redisson;
    private final String keyPrefix;

    public RedisCheckpointStorage(RedissonClient redisson) {
        this(redisson, "checkpoint:");
    }

    public RedisCheckpointStorage(RedissonClient redisson, String keyPrefix) {
        this.redisson = redisson;
        this.keyPrefix = keyPrefix;
    }

    private String getKey(long checkpointId) {
        return keyPrefix + checkpointId;
    }

    @Override
    public void storeCheckpoint(Checkpoint checkpoint) throws Exception {
        String key = getKey(checkpoint.getCheckpointId());
        RBucket<Checkpoint> bucket = redisson.getBucket(key);
        bucket.set(checkpoint);
    }

    @Override
    public Checkpoint loadCheckpoint(long checkpointId) throws Exception {
        String key = getKey(checkpointId);
        RBucket<Checkpoint> bucket = redisson.getBucket(key);
        return bucket.get();
    }

    @Override
    public Checkpoint getLatestCheckpoint() throws Exception {
        // Only completed checkpoints are valid recovery points (B-14): one persisted by
        // triggerCheckpoint but never completed must not be served as "latest".
        for (Checkpoint checkpoint : listCheckpoints(Integer.MAX_VALUE)) {
            if (checkpoint.isCompleted()) {
                return checkpoint;
            }
        }
        return null;
    }

    @Override
    public List<Checkpoint> listCheckpoints(int limit) throws Exception {
        RKeys keys = redisson.getKeys();
        List<Checkpoint> checkpoints = new ArrayList<>();
        // Scan only this storage's prefix (B-15): a plain getKeys() walks the entire
        // Redis keyspace, deserializing every checkpoint of every other user of the DB.
        for (String key : keys.getKeys(KeysScanOptions.defaults().pattern(keyPrefix + "*"))) {
            if (key == null || !key.startsWith(keyPrefix)) continue;
            String suffix = key.substring(keyPrefix.length());
            // Only accept pure numeric checkpoint keys: {keyPrefix}{checkpointId}
            // This avoids accidentally reading auxiliary keys that share the same prefix.
            if (suffix.isBlank() || !suffix.chars().allMatch(Character::isDigit)) {
                continue;
            }
            RBucket<Checkpoint> bucket = redisson.getBucket(key);
            Checkpoint checkpoint = bucket.get();
            if (checkpoint != null) {
                checkpoints.add(checkpoint);
            }
        }

        // Sort by timestamp descending (newest first)
        checkpoints.sort((c1, c2) -> Long.compare(c2.getTimestamp(), c1.getTimestamp()));

        return checkpoints.stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public boolean deleteCheckpoint(long checkpointId) throws Exception {
        String key = getKey(checkpointId);
        RBucket<Checkpoint> bucket = redisson.getBucket(key);
        return bucket.delete();
    }

    @Override
    public int cleanupOldCheckpoints(int keepCount) throws Exception {
        List<Checkpoint> checkpoints = listCheckpoints(Integer.MAX_VALUE);

        if (checkpoints.size() <= keepCount) {
            return 0;
        }

        // Evict incomplete checkpoints first (B-14): they can never be recovered from,
        // so a newer incomplete checkpoint must not displace an older completed one.
        // Within each class the oldest goes first. The number of surviving checkpoints
        // still equals keepCount.
        List<Checkpoint> evictionOrder = new ArrayList<>(checkpoints.size());
        List<Checkpoint> completed = new ArrayList<>();
        for (Checkpoint checkpoint : checkpoints) {
            if (checkpoint.isCompleted()) {
                completed.add(checkpoint);
            } else {
                evictionOrder.add(checkpoint);
            }
        }
        Collections.reverse(evictionOrder); // input is newest-first; oldest incomplete first
        Collections.reverse(completed);
        evictionOrder.addAll(completed);

        int deleteCount = 0;
        int toDelete = checkpoints.size() - keepCount;
        for (int i = 0; i < toDelete; i++) {
            if (deleteCheckpoint(evictionOrder.get(i).getCheckpointId())) {
                deleteCount++;
            }
        }

        return deleteCount;
    }

    @Override
    public void close() {
        // RedissonClient lifecycle is managed externally
    }

    public RedissonClient getRedisson() {
        return redisson;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }
}
