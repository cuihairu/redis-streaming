package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RT-M4: the real-Redis semantics of the stage-then-rename state restore — a corrupted
 * live key is overwritten wholesale from the snapshot, state that grew after the
 * checkpoint is dropped, the index ends up exactly at the snapshot's key set with no
 * staging leftovers, and the state TTL is applied to the restored keys.
 */
@Tag("integration")
class RedisRuntimeCheckpointManagerRestoreSwapIntegrationTest {

    @Test
    void restoreSwapsSnapshotOverLiveStateAndDropsPostCheckpointKeys() {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String job = "rst-" + uid;
        String prefix = "it-rst:" + uid;
        String indexKey = prefix + ":" + job + ":stateKeys";
        String k1 = prefix + ":st:k1";
        String k9 = prefix + ":st:k9";
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        RedissonClient client = Redisson.create(config);
        try {
            RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                    .jobName(job)
                    .stateKeyPrefix(prefix)
                    .checkpointKeyPrefix(prefix + ":cp")
                    .stateTtl(Duration.ofSeconds(120))
                    .checkpointsToKeep(5)
                    .build();
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(client, cfg);

            // live state at checkpoint time: a single map key. The index is written with
            // the same StringCodec the manager reads it with (the engine's own convention).
            client.<String>getSet(indexKey, org.redisson.client.codec.StringCodec.INSTANCE).add(k1);
            RMap<String, String> k1map = client.getMap(k1, org.redisson.client.codec.StringCodec.INSTANCE);
            k1map.put("f", "1");
            Checkpoint cp = mgr.triggerCheckpoint(java.util.List.of());

            // drift after the checkpoint: a new key appears and the checkpointed key is
            // corrupted in place
            client.<String>getSet(indexKey, org.redisson.client.codec.StringCodec.INSTANCE).add(k9);
            client.getMap(k9, org.redisson.client.codec.StringCodec.INSTANCE).put("f", "9");
            k1map.put("f", "CORRUPTED");

            assertTrue(mgr.restoreFromCheckpoint(cp, java.util.List.of()));

            // snapshot value won, the corruption is gone
            assertEquals(java.util.Map.of("f", "1"), k1map.readAllMap());
            // post-checkpoint state is dropped
            assertEquals(0, client.getKeys().countExists(k9));
            // the index is exactly the snapshot key set — no staging leftovers
            Set<String> index = client.<String>getSet(indexKey, org.redisson.client.codec.StringCodec.INSTANCE).readAll();
            assertEquals(java.util.Set.of(k1), index);
            // TTL carried over to the restored key
            assertTrue(k1map.remainTimeToLive() > 0, "restored key should carry the state TTL");
        } finally {
            try {
                client.getKeys().deleteByPattern("*" + prefix + "*");
            } catch (Exception ignore) {
            }
            client.shutdown();
        }
    }
}
