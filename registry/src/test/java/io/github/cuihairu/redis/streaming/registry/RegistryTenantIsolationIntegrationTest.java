package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import io.github.cuihairu.redis.streaming.registry.keys.RegistryKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.RKeys;
import org.redisson.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Real-Redis tenant isolation: two tenants sharing one key prefix must not see
 * each other's instances, and the Lua filter path must only read the caller's
 * tenant keys (Lua builds instance keys as {@code <effectivePrefix>:services:...}).
 */
@Tag("integration")
class RegistryTenantIsolationIntegrationTest {

    private static final String SERVICE = "svc";

    private static RedissonClient redissonClient;

    private final List<RedisServiceProvider> providers = new ArrayList<>();
    private final List<RedisServiceConsumer> consumers = new ArrayList<>();

    @BeforeAll
    static void setupRedis() {
        Config config = new Config();
        config.useSingleServer().setAddress(
                System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        redissonClient = Redisson.create(config);
    }

    @AfterAll
    static void teardownRedis() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }

    @BeforeEach
    void flush() {
        redissonClient.getKeys().flushdb();
    }

    @AfterEach
    void stopAll() {
        for (RedisServiceProvider provider : providers) {
            provider.stop();
        }
        for (RedisServiceConsumer consumer : consumers) {
            consumer.stop();
        }
        providers.clear();
        consumers.clear();
    }

    @Test
    @DisplayName("two tenants on one shared prefix keep their instances apart")
    void tenantsDoNotSeeEachOthersInstances() throws InterruptedException {
        Pair acme = pair("acme");
        Pair beta = pair("beta");
        acme.provider().register(instance("i-acme", "1"));
        beta.provider().register(instance("i-beta", "2"));
        settle();

        assertEquals(List.of("i-acme"), ids(acme.consumer().getAllInstances(SERVICE)));
        assertEquals(List.of("i-beta"), ids(beta.consumer().getAllInstances(SERVICE)));

        List<String> registered = new ArrayList<>();
        redissonClient.getKeys().getKeysByPattern("registry:*:services:svc:instance:*")
                .forEach(registered::add);
        assertEquals(List.of("i-acme", "i-beta"),
                registered.stream().map(k -> k.substring(k.lastIndexOf(':') + 1)).sorted().toList());
    }

    @Test
    @DisplayName("the Lua metadata filter path only reads the caller tenant keys")
    void luaFilterStaysInsideTheTenant() throws InterruptedException {
        Pair acme = pair("acme");
        Pair beta = pair("beta");
        acme.provider().register(instance("i-acme", "1"));
        beta.provider().register(instance("i-beta", "1"));
        settle();

        // both tenants host a version=1 instance under the same service name: only the
        // caller's own tenant may be returned (a raw, non-tenant prefix finds no key)
        assertEquals(List.of("i-acme"),
                ids(acme.consumer().getInstancesByMetadata(SERVICE, Map.of("version", "1"))));
        assertEquals(List.of("i-beta"),
                ids(beta.consumer().getInstancesByMetadata(SERVICE, Map.of("version", "1"))));
    }

    @Test
    @DisplayName("the default tenant keeps the legacy key shape")
    void defaultTenantUsesLegacyKeys() throws InterruptedException {
        Pair legacy = pair(RegistryKeys.DEFAULT_TENANT);
        legacy.provider().register(instance("i-legacy", "1"));
        settle();

        assertEquals(List.of("i-legacy"), ids(legacy.consumer().getAllInstances(SERVICE)));

        List<String> allKeys = new ArrayList<>();
        redissonClient.getKeys().getKeysByPattern("registry:*").forEach(allKeys::add);
        assertEquals("registry:services:svc:instance:i-legacy",
                allKeys.stream().filter(k -> k.endsWith("i-legacy")).findFirst().orElseThrow());
        assertFalse(allKeys.stream().anyMatch(k -> k.startsWith("registry:default:")));
    }

    /** A provider/consumer pair pinned to one tenant on one shared key prefix. */
    private Pair pair(String tenant) {
        ServiceProviderConfig providerConfig = new ServiceProviderConfig("registry");
        providerConfig.setTenant(tenant);

        ServiceConsumerConfig consumerConfig = new ServiceConsumerConfig("registry");
        consumerConfig.setTenant(tenant);

        RedisServiceProvider provider = new RedisServiceProvider(redissonClient, providerConfig);
        RedisServiceConsumer consumer = new RedisServiceConsumer(redissonClient, consumerConfig);
        provider.start();
        consumer.start();
        providers.add(provider);
        consumers.add(consumer);
        return new Pair(provider, consumer);
    }

    private ServiceInstance instance(String instanceId, String version) {
        return DefaultServiceInstance.builder()
                .serviceName(SERVICE)
                .instanceId(instanceId)
                .host("127.0.0.1")
                .port(8080)
                .metadata(Map.of("version", version))
                .build();
    }

    private List<String> ids(List<ServiceInstance> instances) {
        return instances.stream().map(ServiceInstance::getInstanceId).toList();
    }

    private void settle() throws InterruptedException {
        Thread.sleep(1000);
    }

    private record Pair(RedisServiceProvider provider, RedisServiceConsumer consumer) {
    }
}
