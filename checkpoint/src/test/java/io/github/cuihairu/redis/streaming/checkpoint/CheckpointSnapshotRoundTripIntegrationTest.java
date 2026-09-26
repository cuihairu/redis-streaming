package io.github.cuihairu.redis.streaming.checkpoint;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointCoordinator;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-13 regression: checkpoint snapshots survive a real Redis round-trip with their types
 * intact, carry a snapshot version, support typed reads at the call site, and restore
 * actually hands state to the caller instead of only logging success.
 */
@Tag("integration")
class CheckpointSnapshotRoundTripIntegrationTest {

    public static class Widget {
        private String name;
        private int count;

        public Widget() {
        }

        public Widget(String name, int count) {
            this.name = name;
            this.count = count;
        }

        public String getName() {
            return name;
        }

        public int getCount() {
            return count;
        }
    }

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    @Test
    void pojoAndTypedMapStateSurviveTheRoundTrip() throws Exception {
        RedissonClient client = createClient();
        String prefix = "b13-pojo-" + UUID.randomUUID().toString().substring(0, 8) + ":";
        try {
            RedisCheckpointStorage storage = new RedisCheckpointStorage(client, prefix);
            DefaultCheckpoint cp = new DefaultCheckpoint(1, System.currentTimeMillis());
            cp.getStateSnapshot().putState("widget", new Widget("gear", 7));
            Map<Integer, String> offsets = new HashMap<>();
            offsets.put(0, "0-5");
            offsets.put(1, "0-9");
            cp.getStateSnapshot().putState("offsets", offsets);
            cp.markCompleted();
            storage.storeCheckpoint(cp);

            Checkpoint loaded = storage.loadCheckpoint(1);
            Widget widget = loaded.getStateSnapshot().getState("widget", Widget.class);
            assertEquals("gear", widget.getName());
            assertEquals(7, widget.getCount());
            assertSame(widget.getClass(), loaded.getStateSnapshot().getState("widget").getClass(),
                    "the POJO must not degrade to a generic map through the codec");

            @SuppressWarnings("unchecked")
            Map<Integer, String> map = loaded.getStateSnapshot().getState("offsets");
            assertEquals("0-5", map.get(0), "typed map keys must survive the round trip");
            assertEquals("0-9", map.get(1));
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }

    @Test
    void snapshotVersionSurvivesTheRoundTrip() throws Exception {
        RedissonClient client = createClient();
        String prefix = "b13-ver-" + UUID.randomUUID().toString().substring(0, 8) + ":";
        try {
            RedisCheckpointStorage storage = new RedisCheckpointStorage(client, prefix);
            DefaultCheckpoint cp = new DefaultCheckpoint(3, System.currentTimeMillis());
            cp.markCompleted();
            storage.storeCheckpoint(cp);

            Checkpoint loaded = storage.loadCheckpoint(3);
            assertEquals(DefaultCheckpoint.CURRENT_SNAPSHOT_VERSION, loaded.getSnapshotVersion(),
                    "the persisted checkpoint must come back with its version marker");
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }

    @Test
    void restoreHandsEverySnapshotEntryToTheSink() throws Exception {
        RedissonClient client = createClient();
        String prefix = "b13-restore-" + UUID.randomUUID().toString().substring(0, 8) + ":";
        try {
            RedisCheckpointStorage storage = new RedisCheckpointStorage(client, prefix);
            RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1);

            DefaultCheckpoint cp = new DefaultCheckpoint(5, System.currentTimeMillis());
            cp.getStateSnapshot().putState("counter", 42);
            cp.getStateSnapshot().putState("greeting", "hello");
            cp.markCompleted();
            storage.storeCheckpoint(cp);

            Map<String, Object> received = new HashMap<>();
            List<String> order = new ArrayList<>();
            int transferred = coordinator.restoreFromCheckpoint(5, (key, value) -> {
                order.add(key);
                received.put(key, value);
            });

            assertEquals(2, transferred);
            assertEquals(2, received.size());
            assertEquals(42, ((Number) received.get("counter")).intValue());
            assertEquals("hello", received.get("greeting"));
            assertTrue(order.containsAll(List.of("counter", "greeting")));

            coordinator.close();
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }

    @Test
    void restoreRefusesUnknownAndIncompleteCheckpointsWithoutTransferring() throws Exception {
        RedissonClient client = createClient();
        String prefix = "b13-refuse-" + UUID.randomUUID().toString().substring(0, 8) + ":";
        try {
            RedisCheckpointStorage storage = new RedisCheckpointStorage(client, prefix);
            RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1);

            // incomplete checkpoint: persisted by triggerCheckpoint, never acknowledged
            long incompleteId = coordinator.triggerCheckpoint();

            List<String> transferred = new ArrayList<>();
            assertEquals(-1, coordinator.restoreFromCheckpoint(12345, (key, value) -> transferred.add(key)),
                    "an unknown checkpoint must transfer nothing");
            assertEquals(-1, coordinator.restoreFromCheckpoint(incompleteId, (key, value) -> transferred.add(key)),
                    "an incomplete checkpoint must transfer nothing (B-14)");
            assertTrue(transferred.isEmpty());

            coordinator.close();
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }

    @Test
    void typedReadConvertsLegacyDecodedShapes() throws Exception {
        RedissonClient client = createClient();
        String prefix = "b13-legacy-" + UUID.randomUUID().toString().substring(0, 8) + ":";
        try {
            RedisCheckpointStorage storage = new RedisCheckpointStorage(client, prefix);

            // Simulate a pre-typed-read snapshot whose widget state was persisted as a
            // plain field map (as some codecs decode it) instead of the POJO.
            DefaultCheckpoint cp = new DefaultCheckpoint(7, System.currentTimeMillis());
            cp.getStateSnapshot().putState("widget", Map.of("name", "cog", "count", 3));
            cp.markCompleted();
            storage.storeCheckpoint(cp);

            Checkpoint loaded = storage.loadCheckpoint(7);
            Widget widget = loaded.getStateSnapshot().getState("widget", Widget.class);
            assertEquals("cog", widget.getName());
            assertEquals(3, widget.getCount());

            assertNull(loaded.getStateSnapshot().getState("absent", Widget.class));
            assertInstanceOf(Widget.class, loaded.getStateSnapshot().getState("widget", Widget.class));
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }
}
