package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import io.github.cuihairu.redis.streaming.state.redis.RedisStateBackend;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-Redis proof that two tenants running the same job name with the same custom
 * prefixes keep their own state and checkpoints: the tenant segment is derived from
 * the job's tenant, not from the caller's prefix, so nothing lands in a shared key.
 */
@Tag("integration")
class RedisRuntimeTenantIsolationIntegrationTest {

    @Test
    @DisplayName("two tenants on the same job name keep their own state keys")
    void tenantsDoNotShareStateKeys() throws Exception {
        RedissonClient client = createClient();
        String jobName = "job-" + suffix();
        RedisRuntimeConfig acme = config(jobName, "acme");
        RedisRuntimeConfig beta = config(jobName, "beta");
        try {
            assertNotEquals(acme.getStateKeyPrefix(), beta.getStateKeyPrefix());
            assertTrue(acme.getStateKeyPrefix().contains(":acme"), acme.getStateKeyPrefix());
            assertTrue(beta.getStateKeyPrefix().contains(":beta"), beta.getStateKeyPrefix());

            ValueState<String> acmeState = state(client, jobName, acme);
            ValueState<String> betaState = state(client, jobName, beta);
            acmeState.update("v-acme");
            betaState.update("v-beta");

            assertEquals("v-acme", acmeState.value());
            assertEquals("v-beta", betaState.value());

            // re-writing one tenant's state must not disturb the other
            acmeState.update("v-acme-2");
            assertEquals("v-acme-2", acmeState.value());
            assertEquals("v-beta", betaState.value());

            List<String> keys = keys(client, "*" + jobName + "*");
            assertTrue(keys.stream().anyMatch(k -> k.contains(":acme:")), keys.toString());
            assertTrue(keys.stream().anyMatch(k -> k.contains(":beta:")), keys.toString());
            client.getKeys().delete(keys.toArray(new String[0]));
        } finally {
            client.shutdown();
        }
    }

    @Test
    @DisplayName("two tenants on the same job name keep their own checkpoints")
    void tenantsDoNotShareCheckpoints() throws Exception {
        RedissonClient client = createClient();
        String jobName = "job-" + suffix();
        RedisRuntimeConfig acme = config(jobName, "acme");
        RedisRuntimeConfig beta = config(jobName, "beta");
        try {
            assertNotEquals(acme.getCheckpointKeyPrefix(), beta.getCheckpointKeyPrefix());
            // the runtime checkpoint manager appends the job name to the config prefix
            String acmePrefix = acme.getCheckpointKeyPrefix() + jobName + ":";
            String betaPrefix = beta.getCheckpointKeyPrefix() + jobName + ":";
            assertTrue(acmePrefix.contains(":acme:"), acmePrefix);
            assertTrue(betaPrefix.contains(":beta:"), betaPrefix);

            RedisCheckpointStorage acmeStore = new RedisCheckpointStorage(client, acmePrefix);
            RedisCheckpointStorage betaStore = new RedisCheckpointStorage(client, betaPrefix);
            // only completed checkpoints are recovery points (B-14), so mark them as such
            DefaultCheckpoint acmeCheckpoint = new DefaultCheckpoint(1L, 1L);
            acmeCheckpoint.markCompleted();
            DefaultCheckpoint betaCheckpoint = new DefaultCheckpoint(1L, 2L);
            betaCheckpoint.markCompleted();
            acmeStore.storeCheckpoint(acmeCheckpoint);
            betaStore.storeCheckpoint(betaCheckpoint);

            assertEquals(1L, acmeStore.loadCheckpoint(1L).getTimestamp());
            assertEquals(2L, betaStore.loadCheckpoint(1L).getTimestamp());
            // each storage sees exactly its own checkpoint list
            assertEquals(1, acmeStore.listCheckpoints(10).size());
            assertEquals(1, betaStore.listCheckpoints(10).size());
            assertEquals(1L, acmeStore.getLatestCheckpoint().getTimestamp());
            assertEquals(2L, betaStore.getLatestCheckpoint().getTimestamp());

            List<String> keys = keys(client, "*" + jobName + "*");
            assertTrue(keys.stream().anyMatch(k -> k.contains(":acme:")), keys.toString());
            assertTrue(keys.stream().anyMatch(k -> k.contains(":beta:")), keys.toString());
            client.getKeys().delete(keys.toArray(new String[0]));
        } finally {
            client.shutdown();
        }
    }

    @Test
    @DisplayName("the default tenant keeps the legacy prefix shape")
    void defaultTenantKeepsLegacyPrefixes() {
        RedisRuntimeConfig legacy = RedisRuntimeConfig.builder()
                .jobName("job")
                .stateKeyPrefix("custom:state")
                .checkpointKeyPrefix("custom:checkpoint:")
                .sinkDedupKeyPrefix("custom:sinkDedup:")
                .build();

        assertEquals("custom:state", legacy.getStateKeyPrefix());
        assertEquals("custom:checkpoint:", legacy.getCheckpointKeyPrefix());
        assertEquals("custom:sinkDedup:", legacy.getSinkDedupKeyPrefix());
        // no key segment at all for the default tenant: the prefixes stay byte-identical
        assertFalse(legacy.getStateKeyPrefix().contains("default"), legacy.getStateKeyPrefix());
        assertFalse(legacy.getCheckpointKeyPrefix().contains("default"), legacy.getCheckpointKeyPrefix());
    }

    private RedisRuntimeConfig config(String jobName, String tenant) {
        // custom prefixes on purpose: this is the case that used to drop the tenant
        return RedisRuntimeConfig.builder()
                .jobName(jobName)
                .stateKeyPrefix("tnl:state")
                .checkpointKeyPrefix("tnl:checkpoint:")
                .sinkDedupKeyPrefix("tnl:sinkDedup:")
                .tenant(tenant)
                .build();
    }

    private ValueState<String> state(RedissonClient client, String jobName, RedisRuntimeConfig config) {
        RedisStateBackend backend = new RedisStateBackend(client, jobName, config.getStateKeyPrefix());
        return backend.createValueState(new StateDescriptor<>("counter", String.class, "none"));
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private List<String> keys(RedissonClient client, String pattern) {
        RKeys keys = client.getKeys();
        List<String> result = new ArrayList<>();
        keys.getKeysByPattern(pattern).forEach(result::add);
        return result;
    }

    private RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(
                        System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"))
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}