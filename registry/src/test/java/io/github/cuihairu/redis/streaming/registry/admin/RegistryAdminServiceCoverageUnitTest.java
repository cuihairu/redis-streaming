package io.github.cuihairu.redis.streaming.registry.admin;

import io.github.cuihairu.redis.streaming.registry.BaseRedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Branch-complete unit coverage for RegistryAdminService detail parsing, active
 * instance collection, metrics/health aggregation and cleanup bookkeeping.
 */
class RegistryAdminServiceCoverageUnitTest {

    private RedissonClient redisson;
    private RScript script;
    private RegistryAdminService admin;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        when(redisson.getScript(any(StringCodec.class))).thenReturn(script);
        when(script.scriptLoad(anyString())).thenAnswer(inv -> "sha-" + Math.abs(inv.getArgument(0).hashCode()));
        admin = new RegistryAdminService(redisson, new BaseRedisConfig());
    }

    @SuppressWarnings("unchecked")
    private void stubInstance(String serviceName, String instanceId, Map<String, String> data) {
        RMap<String, String> map = mock(RMap.class);
        when(map.isExists()).thenReturn(true);
        when(map.readAllMap()).thenReturn(data);
        when(redisson.<String, String>getMap(
                eq(new BaseRedisConfig().getRegistryKeys().getServiceInstanceKey(serviceName, instanceId)),
                any(StringCodec.class))).thenReturn(map);
    }

    private static Map<String, String> fullInstanceData() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "10.1.2.3");
        data.put("port", "8080");
        data.put("protocol", "http");
        data.put("enabled", "true");
        data.put("healthy", "true");
        data.put("weight", "5");
        data.put("registrationTime", "1000");
        data.put("lastHeartbeatTime", "2000");
        data.put("lastMetadataUpdate", "3000");
        data.put("metadata", "{\"region\":\"cn\",\"weight\":\"gold\"}");
        data.put("metrics", "{\"cpu\":0.25,\"mem\":{\"heap\":42},\"latency\":7}");
        return data;
    }

    @Test
    void parseInstanceDetailsHandlesFullBadAndPartialData() {
        stubInstance("svc", "full", fullInstanceData());
        InstanceDetails full = admin.getInstanceDetails("svc", "full");
        assertNotNull(full);
        assertEquals("10.1.2.3", full.getHost());
        assertEquals(8080, full.getPort());
        assertEquals("http", full.getProtocol());
        assertEquals(5, full.getWeight());
        assertEquals("cn", full.getMetadata().get("region"));
        assertTrue(full.getMetrics().containsKey("cpu"));

        // bad numbers, bad metadata json, bad metrics json -> safe defaults
        Map<String, String> dirty = new HashMap<>();
        dirty.put("host", "h");
        dirty.put("port", "not-a-port");
        dirty.put("weight", "heavy");
        dirty.put("registrationTime", "yesterday");
        dirty.put("lastHeartbeatTime", "later");
        dirty.put("lastMetadataUpdate", "soon");
        dirty.put("enabled", "not-bool");
        dirty.put("healthy", "not-bool");
        dirty.put("metadata", "{{bad");
        dirty.put("metrics", "{{bad");
        stubInstance("svc", "dirty", dirty);
        InstanceDetails parsed = admin.getInstanceDetails("svc", "dirty");
        assertNotNull(parsed);
        assertEquals(0, parsed.getPort());
        assertEquals(1, parsed.getWeight());
        assertEquals(0, parsed.getRegistrationTime());
        assertTrue(parsed.getMetadata().isEmpty());
        assertTrue(parsed.getMetrics().isEmpty());

        // missing optional fields -> defaults and empty maps
        Map<String, String> minimal = new HashMap<>();
        minimal.put("host", "h");
        minimal.put("metadata", "");
        minimal.put("metrics", "");
        stubInstance("svc", "min", minimal);
        InstanceDetails min = admin.getInstanceDetails("svc", "min");
        assertNotNull(min);
        assertEquals(0, min.getLastHeartbeatTime());
        assertTrue(min.getMetrics().isEmpty());

        // missing key and failure paths
        assertNull(admin.getInstanceDetails("svc", "absent"));
        RMap<String, String> absent = mock(RMap.class);
        when(absent.isExists()).thenReturn(false);
        when(redisson.<String, String>getMap(anyString(), any(StringCodec.class))).thenReturn(absent);
        assertNull(admin.getInstanceDetails("svc", "none"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getActiveInstancesParsesHeartbeatTimeTypesAndSkipsBrokenRows() {
        stubInstance("svc", "i1", fullInstanceData());
        stubInstance("svc", "i2", fullInstanceData());
        RMap<String, String> missing = mock(RMap.class);
        when(missing.isExists()).thenReturn(false);
        when(redisson.<String, String>getMap(
                eq(new BaseRedisConfig().getRegistryKeys().getServiceInstanceKey("svc", "i3")),
                any(StringCodec.class))).thenReturn(missing);

        when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any())).thenReturn(List.of(
                "i1", "12345",
                "i2", 67890L,
                "i3", "1",
                "i4", new Object()));

        List<InstanceDetails> active = admin.getActiveInstances("svc", Duration.ofMinutes(1));
        assertEquals(2, active.size());
        assertEquals(12345L, active.get(0).getLastHeartbeatTime());
        assertEquals(67890L, active.get(1).getLastHeartbeatTime());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getActiveInstancesReturnsEmptyOnFailure() {
        when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any())).thenThrow(new IllegalStateException("lua broken"));
        assertTrue(admin.getActiveInstances("svc", Duration.ofMinutes(1)).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getServiceDetailsAggregatesNestedMetricsAndHandlesEmpty() {
        stubInstance("svc", "i1", fullInstanceData());
        when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any())).thenReturn(List.of("i1", "1"));

        ServiceDetails details = admin.getServiceDetails("svc", Duration.ofMinutes(1));
        assertEquals("svc", details.getServiceName());
        assertEquals(1, details.getInstances().size());
        Map<String, Object> aggregated = details.getAggregatedMetrics();
        assertNotNull(aggregated);
        assertTrue(aggregated.containsKey("avgHeartbeatDelay"));
        assertTrue(aggregated.containsKey("cpu"));
        assertTrue(aggregated.containsKey("mem.heap"));

        // no active instances -> no aggregated metrics
        when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any())).thenReturn(List.of());
        ServiceDetails empty = admin.getServiceDetails("svc", Duration.ofMinutes(1));
        assertTrue(empty.getInstances().isEmpty());

        // default timeout overload
        assertNotNull(admin.getServiceDetails("svc"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getInstanceMetricsCoversFoundMissingAndFailurePaths() {
        stubInstance("svc", "i1", fullInstanceData());
        assertTrue(admin.getInstanceMetrics("svc", "i1").containsKey("cpu"));

        RMap<String, String> absent = mock(RMap.class);
        when(absent.isExists()).thenReturn(false);
        when(redisson.<String, String>getMap(anyString(), any(StringCodec.class))).thenReturn(absent);
        assertTrue(admin.getInstanceMetrics("svc", "none").isEmpty());

        when(redisson.<String, String>getMap(anyString(), any(StringCodec.class)))
                .thenThrow(new IllegalStateException("map gone"));
        assertTrue(admin.getInstanceMetrics("svc", "broken").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getRegistryHealthCountsHealthyAndActiveInstances() {
        Map<String, String> unhealthy = new HashMap<>(fullInstanceData());
        unhealthy.put("healthy", "false");
        stubInstance("svc", "i1", fullInstanceData());
        stubInstance("svc", "i2", unhealthy);

        @SuppressWarnings("rawtypes")
        RSet services = mock(RSet.class);
        when(services.readAll()).thenReturn(Set.of("svc"));
        when(redisson.getSet(anyString(), any(StringCodec.class))).thenReturn(services);
        when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any())).thenReturn(List.of("i1", "1", "i2", "1"));

        Map<String, Object> health = admin.getRegistryHealth();
        assertEquals(1, health.get("totalServices"));
        assertEquals(2, health.get("totalInstances"));
        assertEquals(1, health.get("healthyInstances"));
        assertEquals(2, health.get("activeInstances"));
        assertEquals(50.0, (Double) health.get("healthyRate"), 0.001);

        // empty registry -> zero healthy rate
        when(services.readAll()).thenReturn(Set.of());
        Map<String, Object> empty = admin.getRegistryHealth();
        assertEquals(0, empty.get("totalServices"));
        assertEquals(0.0, (Double) empty.get("healthyRate"), 0.001);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanupExpiredInstancesTracksPerServiceCountsAndFailures() {
        RSet<String> services = mock(RSet.class);
        when(services.readAll()).thenReturn(Set.of("svc-ok", "svc-bad"));
        when(redisson.<String>getSet(anyString(), any(StringCodec.class))).thenReturn(services);

        // first service succeeds, second service's lua call fails -> -1 entry
        when(script.evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any(), any(), any()))
                .thenReturn(List.of("dead1", "dead2"))
                .thenThrow(new IllegalStateException("lua broken"));

        Map<String, Integer> result = admin.cleanupExpiredInstances(Duration.ofMinutes(1));
        assertEquals(2, result.size());
        assertTrue(result.values().contains(2));
        assertTrue(result.values().contains(-1));

        // outer failure still returns a map
        when(redisson.<String>getSet(anyString(), any(StringCodec.class)))
                .thenThrow(new IllegalStateException("index gone"));
        assertDoesNotThrow(() -> admin.cleanupExpiredInstances(Duration.ofMinutes(1)));
    }

    @Test
    void getAllServicesFailureReturnsEmptySet() {
        assertDoesNotThrow(admin::getAllServices);
    }
}
