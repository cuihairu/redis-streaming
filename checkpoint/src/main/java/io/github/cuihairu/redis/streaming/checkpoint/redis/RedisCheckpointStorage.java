package io.github.cuihairu.redis.streaming.checkpoint.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import io.github.cuihairu.redis.streaming.checkpoint.storage.CheckpointStorage;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

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
        List<Checkpoint> checkpoints = listCheckpoints(1);
        return checkpoints.isEmpty() ? null : checkpoints.get(0);
    }

    @Override
    public List<Checkpoint> listCheckpoints(int limit) throws Exception {
        RKeys keys = redisson.getKeys();
        List<Checkpoint> checkpoints = new ArrayList<>();
        for (String key : keys.getKeys()) {
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

        int deleteCount = 0;
        // Skip the first 'keepCount' checkpoints (newest)
        for (int i = keepCount; i < checkpoints.size(); i++) {
            if (deleteCheckpoint(checkpoints.get(i).getCheckpointId())) {
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
