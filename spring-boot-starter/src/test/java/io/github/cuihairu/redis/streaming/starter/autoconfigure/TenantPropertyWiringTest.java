package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.config.ConfigService;
import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumer;
import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tenant properties reach every registry / config-center bean: a configured tenant
 * scopes the Redis keys, the default tenant keeps the legacy key shape, and an
 * illegal tenant name fails the startup instead of silently sharing a keyspace.
 */
class TenantPropertyWiringTest {

    private static final String REGISTRY_PREFIX = "redis_streaming_registry";
    private static final String CONFIG_PREFIX = "redis_streaming";

    @Test
    void registryTenantScopesTheNamingServiceKeys() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setTenant("acme");

        NamingServiceHandle handle = namingService(props);
        try {
            assertEquals("acme", handle.tenant());
            assertEquals(REGISTRY_PREFIX + ":acme:services:svc:instance:i1", handle.instanceKey("svc", "i1"));
        } finally {
            handle.stop();
        }
    }

    @Test
    void discoveryConsumerHonorsTheRegistryTenant() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setTenant("team-a.1");

        ServiceConsumer consumer = new RedisStreamingDiscoveryAutoConfiguration().serviceConsumer(mock(RedissonClient.class), props);
        try {
            NamingServiceHandle handle = new NamingServiceHandle((RedisNamingService) consumer);
            assertEquals("team-a.1", handle.tenant());
            assertEquals(REGISTRY_PREFIX + ":team-a.1:services:svc:heartbeats", handle.heartbeatsKey("svc"));
        } finally {
            consumer.stop();
        }
    }

    @Test
    void configTenantScopesTheConfigKeys() throws Exception {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getConfig().setTenant("acme");

        ConfigService service = configService(props);
        try {
            ConfigServiceConfigHandle handle = new ConfigServiceConfigHandle((RedisConfigService) service);
            assertEquals("acme", handle.tenant());
            assertEquals(CONFIG_PREFIX + ":acme:config:g:d", handle.configKey("g", "d"));
            assertEquals(CONFIG_PREFIX + ":acme:config_history:g:d", handle.historyKey("g", "d"));
        } finally {
            service.stop();
        }
    }

    @Test
    void unsetTenantKeepsTheLegacyKeys() throws Exception {
        NamingServiceHandle registry = namingService(new RedisStreamingProperties());
        ConfigService service = configService(new RedisStreamingProperties());
        try {
            assertEquals("default", registry.tenant());
            assertEquals(REGISTRY_PREFIX + ":services:svc:instance:i1", registry.instanceKey("svc", "i1"));
            ConfigServiceConfigHandle config = new ConfigServiceConfigHandle((RedisConfigService) service);
            assertEquals("default", config.tenant());
            assertEquals(CONFIG_PREFIX + ":config:g:d", config.configKey("g", "d"));
        } finally {
            registry.stop();
            service.stop();
        }
    }

    @Test
    void blankTenantFallsBackToTheDefaultTenant() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setTenant("   ");
        props.getConfig().setTenant("");

        NamingServiceHandle registry = namingService(props);
        ConfigService service = configService(props);
        try {
            assertEquals("default", registry.tenant());
            assertEquals(REGISTRY_PREFIX + ":services:svc:instance:i1", registry.instanceKey("svc", "i1"));
            assertEquals("default", configTenant(service));
            assertEquals(CONFIG_PREFIX + ":config:g:d", configKey(service, "g", "d"));
        } finally {
            registry.stop();
            service.stop();
        }
    }

    @Test
    void illegalTenantNameFailsStartup() {
        RedisStreamingProperties registryProps = new RedisStreamingProperties();
        registryProps.getRegistry().setTenant("bad:name");
        IllegalArgumentException registryFailure = assertThrows(IllegalArgumentException.class,
                () -> namingService(registryProps));
        assertTrue(registryFailure.getMessage().contains("tenant"));

        RedisStreamingProperties configProps = new RedisStreamingProperties();
        configProps.getConfig().setTenant("has space");
        assertThrows(IllegalArgumentException.class, () -> configService(configProps));
    }

    // ===== helpers =====

    private NamingServiceHandle namingService(RedisStreamingProperties props) {
        RedisNamingService service = (RedisNamingService) new RedisStreamingRegistryAutoConfiguration()
                .namingService(mock(RedissonClient.class), props);
        assertTrue(service.isRunning());
        return new NamingServiceHandle(service);
    }

    private ConfigService configService(RedisStreamingProperties props) {
        @SuppressWarnings("unchecked")
        ObjectProvider<io.github.cuihairu.redis.streaming.config.ContentCipher> noCipher = mock(ObjectProvider.class);
        ConfigService service = new RedisStreamingConfigServiceAutoConfiguration()
                .configService(mock(RedissonClient.class), props, noCipher);
        assertTrue(service.isRunning());
        return service;
    }

    private String configTenant(ConfigService service) {
        return new ConfigServiceConfigHandle((RedisConfigService) service).tenant();
    }

    private String configKey(ConfigService service, String group, String dataId) {
        return new ConfigServiceConfigHandle((RedisConfigService) service).configKey(group, dataId);
    }

    /** The registry config behind a started NamingService, reduced to what the tests assert. */
    private record NamingServiceHandle(RedisNamingService service) {
        void stop() {
            service.stop();
        }

        String tenant() {
            return service.getConfig().getTenant();
        }

        String instanceKey(String serviceName, String instanceId) {
            return service.getConfig().getServiceInstanceKey(serviceName, instanceId);
        }

        String heartbeatsKey(String serviceName) {
            return service.getConfig().getHeartbeatKey(serviceName);
        }
    }

    /** The config-center config behind a started ConfigService (a private field). */
    private record ConfigServiceConfigHandle(io.github.cuihairu.redis.streaming.config.ConfigServiceConfig config) {
        ConfigServiceConfigHandle(RedisConfigService service) {
            this(readConfig(service));
        }

        private static io.github.cuihairu.redis.streaming.config.ConfigServiceConfig readConfig(RedisConfigService service) {
            try {
                Field field = RedisConfigService.class.getDeclaredField("config");
                field.setAccessible(true);
                return (io.github.cuihairu.redis.streaming.config.ConfigServiceConfig) field.get(service);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot read RedisConfigService.config", e);
            }
        }

        String tenant() {
            return config.getTenant();
        }

        String configKey(String group, String dataId) {
            return config.getConfigKey(group, dataId);
        }

        String historyKey(String group, String dataId) {
            return config.getConfigHistoryKey(group, dataId);
        }
    }
}