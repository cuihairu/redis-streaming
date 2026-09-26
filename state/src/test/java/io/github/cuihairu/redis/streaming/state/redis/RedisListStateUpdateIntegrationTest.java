package io.github.cuihairu.redis.streaming.state.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B-38 end-to-end regression on real Redis: {@code update} replaces the list in a
 * single REDIS_WRITE_ATOMIC batch, so replacement is complete and an empty update
 * clears the key — without ever leaving a hybrid old/new state. (The atomicity
 * itself is guaranteed by MULTI/EXEC; the deterministic pre-fix discriminator is
 * the mock-based {@link RedisListStateTest}.)
 */
@Tag("integration")
class RedisListStateUpdateIntegrationTest {

    private RedissonClient client;
    private String key;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        client = Redisson.create(config);
        key = "b38-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        client.getKeys().delete(key);
        client.shutdown();
    }

    @Test
    void updateReplacesTheWholeList() {
        RedisListState<String> state = new RedisListState<>(client, key, String.class);
        state.add("old1");
        state.add("old2");

        state.update(List.of("new1", "new2", "new3"));

        assertEquals(List.of("new1", "new2", "new3"), state.get(),
                "update must fully replace the old contents");
    }

    @Test
    void emptyUpdateClearsTheList() {
        RedisListState<String> state = new RedisListState<>(client, key, String.class);
        state.add("x");

        state.update(List.of());

        assertEquals(List.of(), state.get(), "an empty update must leave the list empty");
    }
}
