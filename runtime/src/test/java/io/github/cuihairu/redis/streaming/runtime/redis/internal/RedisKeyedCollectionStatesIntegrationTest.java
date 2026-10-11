package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-Redis coverage for the keyed Map/List/Set state (state unification step 2): the
 * per-current-key layout inside the keyed state namespace, stateKeys index registration,
 * sliding TTL, clear semantics — and the checkpoint snapshot/restore round trip for the
 * new LIST/SET snapshot value types.
 */
@Tag("integration")
class RedisKeyedCollectionStatesIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    private static RedisKeyedStateStore<Object> store(RedissonClient client, String prefix, String job,
                                                      Duration ttl) {
        return new RedisKeyedStateStore<>(client, new ObjectMapper(), prefix, job, "t1", "g1", "op1",
                ttl, 0, 1, 0L, Duration.ofMinutes(1),
                RedisRuntimeConfig.HotKeyPolicy.LOG_ONLY, 200, false,
                RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
    }

    @Test
    void mapListSetRoundTripWithLayoutIndexAndTtl() {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "cs-" + uid;
        String job = "j" + uid;
        try {
            RedisKeyedStateStore<Object> s = store(client, prefix, job, Duration.ofSeconds(60));
            s.setCurrentPartitionId(0);

            s.setCurrentKey("a");
            var map = s.getMapState("m", String.class, Integer.class);
            var list = s.getListState(new StateDescriptor<>("l", Integer.class));
            var set = s.getSetState(new StateDescriptor<>("s", String.class));

            map.put("x", 1);
            map.put("y", 2);
            list.add(10);
            list.add(11);
            set.add("e1");
            set.add("e2");

            java.util.Map<String, Integer> seen = new java.util.LinkedHashMap<>();
            map.entries().forEach(e -> seen.put(e.getKey(), e.getValue()));
            assertEquals(2, seen.size());
            assertEquals(1, map.get("x"));
            assertNull(map.get("zz"));
            assertEquals(List.of(10, 11), list.get());
            java.util.Set<String> members = new java.util.HashSet<>();
            set.get().forEach(members::add);
            assertEquals(Set.of("e1", "e2"), members);
            assertTrue(set.contains("e1"));
            assertEquals(2, set.size());

            // per-current-key layout inside the keyed state namespace
            String mapKey = prefix + ":" + job + ":cg:g1:topic:t1:p:0:state:op1:m:s:a";
            String listKey = prefix + ":" + job + ":cg:g1:topic:t1:p:0:state:op1:l:s:a";
            String setKey = prefix + ":" + job + ":cg:g1:topic:t1:p:0:state:op1:s:s:a";
            RMap<String, String> mapHash = client.getMap(mapKey, StringCodec.INSTANCE);
            assertEquals("1", mapHash.get("\"x\""));
            RList<String> redisList = client.getList(listKey, StringCodec.INSTANCE);
            assertEquals(List.of("10", "11"), redisList.readAll());
            RSet<String> redisSet = client.getSet(setKey, StringCodec.INSTANCE);
            assertEquals(Set.of("\"e1\"", "\"e2\""), redisSet.readAll());

            // registered in the checkpoint stateKeys index
            Set<String> index = client.<String>getSet(prefix + ":" + job + ":stateKeys",
                    StringCodec.INSTANCE).readAll();
            assertTrue(index.contains(mapKey), "map key should be in stateKeys");
            assertTrue(index.contains(listKey), "list key should be in stateKeys");
            assertTrue(index.contains(setKey), "set key should be in stateKeys");

            // sliding TTL applied at access time
            assertTrue(mapHash.remainTimeToLive() > 0, "map key should carry the state TTL");
            assertTrue(redisList.remainTimeToLive() > 0, "list key should carry the state TTL");
            assertTrue(redisSet.remainTimeToLive() > 0, "set key should carry the state TTL");

            // per-key removals and whole-state clears
            map.remove("x");
            assertNull(map.get("x"));
            map.clear();
            assertTrue(map.isEmpty());
            list.update(List.of(99));
            assertEquals(List.of(99), list.get());
            list.clear();
            assertTrue(!list.get().iterator().hasNext());
            set.remove("e1");
            java.util.Set<String> afterRemove = new java.util.HashSet<>();
            set.get().forEach(afterRemove::add);
            assertEquals(Set.of("e2"), afterRemove);
            set.clear();
            assertTrue(set.isEmpty());
            assertEquals(0, client.getKeys().countExists(mapKey));
        } finally {
            try {
                client.getKeys().deleteByPattern("*" + prefix + "*");
            } catch (Exception ignore) {
            }
            client.shutdown();
        }
    }

    @Test
    void checkpointSnapshotsAndRestoresListAndSetState() {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "csl-" + uid;
        String job = "j" + uid;
        try {
            RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                    .jobName(job)
                    .stateKeyPrefix(prefix)
                    .checkpointKeyPrefix(prefix + ":cp")
                    .stateTtl(Duration.ofSeconds(120))
                    .checkpointsToKeep(5)
                    .build();
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(client, cfg);
            RedisKeyedStateStore<Object> s = store(client, prefix, job, Duration.ofSeconds(120));
            s.setCurrentPartitionId(0);
            s.setCurrentKey("a");
            var list = s.getListState(new StateDescriptor<>("l", Integer.class));
            var set = s.getSetState(new StateDescriptor<>("s", String.class));
            list.add(1);
            list.add(2);
            set.add("e1");

            Checkpoint cp = mgr.triggerCheckpoint(List.of());

            // drift + corruption after the checkpoint
            s.setCurrentKey("b");
            list.add(999);          // a new per-key structure appears
            s.setCurrentKey("a");
            list.update(List.of(7)); // the checkpointed list is corrupted in place
            set.add("drift");

            assertTrue(mgr.restoreFromCheckpoint(cp, List.of()));

            RedisKeyedStateStore<Object> fresh = store(client, prefix, job, Duration.ofSeconds(120));
            fresh.setCurrentPartitionId(0);
            fresh.setCurrentKey("a");
            var list2 = fresh.getListState(new StateDescriptor<>("l", Integer.class));
            var set2 = fresh.getSetState(new StateDescriptor<>("s", String.class));
            // snapshot value won, the corruption is gone
            assertEquals(List.of(1, 2), list2.get());
            java.util.Set<String> restored = new java.util.HashSet<>();
            set2.get().forEach(restored::add);
            assertEquals(Set.of("e1"), restored);
            // post-checkpoint per-key state is dropped
            fresh.setCurrentKey("b");
            assertFalse(fresh.getListState(new StateDescriptor<>("l", Integer.class)).get().iterator().hasNext());
            // restored keys carry the state TTL
            String listKey = prefix + ":" + job + ":cg:g1:topic:t1:p:0:state:op1:l:s:a";
            String setKey = prefix + ":" + job + ":cg:g1:topic:t1:p:0:state:op1:s:s:a";
            assertTrue(client.getList(listKey, StringCodec.INSTANCE).remainTimeToLive() > 0,
                    "restored list key should carry the state TTL");
            assertTrue(client.getSet(setKey, StringCodec.INSTANCE).remainTimeToLive() > 0,
                    "restored set key should carry the state TTL");
        } finally {
            try {
                client.getKeys().deleteByPattern("*" + prefix + "*");
            } catch (Exception ignore) {
            }
            client.shutdown();
        }
    }
}
