package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-Redis tenant isolation for the config center: two tenants may use the same
 * group/dataId without reading each other's content, and the default tenant keeps
 * the legacy key shape.
 */
@Tag("integration")
class ConfigServiceTenantIsolationIntegrationTest {

    private static final String PREFIX = BaseRedisConfig.DEFAULT_KEY_PREFIX;

    @Test
    @DisplayName("two tenants on the same group/dataId keep their configs apart")
    void tenantsDoNotShareConfigKeys() {
        RedissonClient client = createClient();
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String group = "g-" + suffix;
        String dataId = "d-" + suffix;

        RedisConfigService acme = service("acme");
        RedisConfigService beta = service("beta");
        try {
            assertTrue(acme.publishConfig(dataId, group, "v-acme"));
            assertTrue(beta.publishConfig(dataId, group, "v-beta"));

            assertEquals("v-acme", acme.getConfig(dataId, group));
            assertEquals("v-beta", beta.getConfig(dataId, group));

            // republishing in one tenant must not bleed into the other
            assertTrue(acme.publishConfig(dataId, group, "v-acme-2"));
            assertEquals("v-acme-2", acme.getConfig(dataId, group));
            assertEquals("v-beta", beta.getConfig(dataId, group));

            // scope the scan to this test's own group/dataId: the shared Redis DB
            // also holds keys left behind by other integration tests
            List<String> keys = keys(client, PREFIX + ":*:config:" + group + ":" + dataId);
            String acmeKey = keys.stream().filter(k -> k.contains(":acme:")).findFirst().orElseThrow();
            String betaKey = keys.stream().filter(k -> k.contains(":beta:")).findFirst().orElseThrow();
            assertEquals(acmeKey, betaKey.replace(":beta:", ":acme:"));
            assertFalse(keys.stream().anyMatch(k -> k.contains(":default:")));
        } finally {
            acme.stop();
            beta.stop();
            client.shutdown();
        }
    }

    @Test
    @DisplayName("the default tenant keeps the legacy key shape")
    void defaultTenantUsesLegacyKeys() {
        RedissonClient client = createClient();
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String group = "g-" + suffix;
        String dataId = "d-" + suffix;

        RedisConfigService legacy = service(null);
        try {
            assertTrue(legacy.publishConfig(dataId, group, "v"));
            assertEquals("v", legacy.getConfig(dataId, group));

            // exactly one key for this dataId, on the legacy layout (no tenant segment)
            List<String> keys = keys(client, PREFIX + ":config:" + group + ":" + dataId);
            assertEquals(1, keys.size());
            assertEquals(PREFIX + ":config:" + group + ":" + dataId, keys.get(0));
        } finally {
            legacy.stop();
            client.shutdown();
        }
    }

    private RedisConfigService service(String tenant) {
        ConfigServiceConfig config = new ConfigServiceConfig();
        config.setTenant(tenant);
        RedisConfigService service = new RedisConfigService(createClient(), config);
        service.start();
        return service;
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
