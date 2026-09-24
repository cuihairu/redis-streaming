package io.github.cuihairu.redis.streaming.registry.admin;

import io.github.cuihairu.redis.streaming.registry.BaseRedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Residual coverage for RegistryAdminService failure branches. A test subclass throws from
 * the public seams at precise points so the previously unreachable catch blocks run.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RegistryAdminServiceResidualCoverageTest {

    /** Injects failures into seams the implementation normally protects against. */
    static class FaultyAdmin extends RegistryAdminService {
        volatile RuntimeException activeFail;
        volatile RuntimeException instanceFail;
        volatile RuntimeException servicesFail;

        FaultyAdmin(RedissonClient client, BaseRedisConfig config) {
            super(client, config);
        }

        @Override
        public List<InstanceDetails> getActiveInstances(String serviceName, Duration timeout) {
            if (activeFail != null) {
                throw activeFail;
            }
            return super.getActiveInstances(serviceName, timeout);
        }

        @Override
        public InstanceDetails getInstanceDetails(String serviceName, String instanceId) {
            if (instanceFail != null) {
                throw instanceFail;
            }
            return super.getInstanceDetails(serviceName, instanceId);
        }

        @Override
        public Set<String> getAllServices() {
            if (servicesFail != null) {
                throw servicesFail;
            }
            return super.getAllServices();
        }
    }

    private RedissonClient redisson;
    private RScript script;
    private RSet<String> servicesSet;
    private FaultyAdmin admin;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        servicesSet = mock(RSet.class);
        when(redisson.getScript(any(StringCodec.class))).thenReturn(script);
        when(script.scriptLoad(anyString())).thenAnswer(inv -> "sha-" + Math.abs(inv.getArgument(0).hashCode()));
        when(redisson.getSet(anyString(), any(StringCodec.class))).thenReturn((RSet) servicesSet);
        admin = new FaultyAdmin(redisson, new BaseRedisConfig());
    }

    @Test
    void getServiceDetailsReturnsErrorDetailsWhenActiveLookupFails() {
        admin.activeFail = new IllegalStateException("active boom");
        ServiceDetails details = admin.getServiceDetails("svc");
        assertEquals("svc", details.getServiceName());
        assertTrue(details.getInstances().isEmpty());
    }

    @Test
    void getActiveInstancesSkipsInstancesWhoseDetailsThrow() {
        when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any())).thenReturn(List.of("i1", 12345L));
        admin.instanceFail = new IllegalStateException("details boom");
        assertTrue(admin.getActiveInstances("svc", Duration.ofMinutes(1)).isEmpty());
    }

    @Test
    void getInstanceMetricsReturnsEmptyWhenDetailsThrow() {
        admin.instanceFail = new IllegalStateException("details boom");
        assertTrue(admin.getInstanceMetrics("svc", "i1").isEmpty());
    }

    @Test
    void getRegistryHealthCatchesPerServiceFailureAndOuterFailure() {
        when(servicesSet.readAll()).thenReturn(Set.of("svc-a"));
        admin.activeFail = new IllegalStateException("active boom");
        Map<String, Object> health = admin.getRegistryHealth();
        assertEquals(1, health.get("totalServices"));
        assertEquals(0, health.get("totalInstances"));

        admin.servicesFail = new IllegalStateException("services boom");
        Map<String, Object> error = admin.getRegistryHealth();
        assertTrue(error.containsKey("error"));
        assertNotNull(error.get("timestamp"));
    }

    @Test
    void parseInstanceDetailsReturnsNullOnHostileData() throws Exception {
        Method m = RegistryAdminService.class.getDeclaredMethod(
                "parseInstanceDetails", String.class, String.class, Map.class);
        m.setAccessible(true);
        assertNull(m.invoke(admin, "svc", "i1", (Object) null));
    }

    @Test
    void cleanupExpiredInstancesHandlesNullKeyPrefix() {
        BaseRedisConfig cfg = mock(BaseRedisConfig.class);
        when(cfg.getRegistryKeys()).thenReturn(new BaseRedisConfig().getRegistryKeys());
        when(cfg.getKeyPrefix()).thenReturn(null);
        RegistryAdminService nullPrefix = new RegistryAdminService(redisson, cfg);
        when(servicesSet.readAll()).thenReturn(Set.of());
        Map<String, Integer> result = nullPrefix.cleanupExpiredInstances(Duration.ofSeconds(1));
        assertTrue(result.isEmpty());
    }

    @Test
    void getInstanceMetricsReturnsParsedMetrics() {
        RMap<String, String> map = mock(RMap.class);
        when(map.isExists()).thenReturn(true);
        Map<String, String> data = new HashMap<>();
        data.put("host", "h");
        data.put("metrics", "{\"cpu\":0.5}");
        when(map.readAllMap()).thenReturn(data);
        when(redisson.<String, String>getMap(
                eq(new BaseRedisConfig().getRegistryKeys().getServiceInstanceKey("svc", "i9")),
                any(StringCodec.class))).thenReturn(map);
        Map<String, Object> metrics = admin.getInstanceMetrics("svc", "i9");
        assertEquals(0.5, (Double) metrics.get("cpu"));
    }
}
