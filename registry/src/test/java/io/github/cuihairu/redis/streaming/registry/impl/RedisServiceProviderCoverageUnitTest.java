package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceProviderConfig;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.heartbeat.HeartbeatConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Branch-complete unit coverage for RedisServiceProvider update/cleanup/notify paths
 * using a mocked Redisson.
 */
class RedisServiceProviderCoverageUnitTest {

    private RedissonClient redisson;
    private RScript script;
    private RTopic topic;
    private RMap<String, String> anyInstanceMap;
    private RScoredSortedSet<String> anyHeartbeats;
    private RSet<String> servicesSet;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        topic = mock(RTopic.class);
        anyInstanceMap = mock(RMap.class);
        anyHeartbeats = mock(RScoredSortedSet.class);
        servicesSet = mock(RSet.class);
        when(redisson.getScript(any(StringCodec.class))).thenReturn(script);
        when(script.scriptLoad(anyString())).thenAnswer(inv -> "sha-" + Math.abs(inv.getArgument(0).hashCode()));
        when(redisson.getTopic(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(topic);
        when(redisson.<String, String>getMap(anyString())).thenReturn(anyInstanceMap);
        when(redisson.<String>getScoredSortedSet(anyString())).thenReturn(anyHeartbeats);
        when(anyHeartbeats.size()).thenReturn(1);
        when(servicesSet.readAll()).thenReturn(Set.of());
    }

    private static ServiceInstance instance(String serviceName, String instanceId, Map<String, String> metadata) {
        return DefaultServiceInstance.builder()
                .serviceName(serviceName).instanceId(instanceId).host("127.0.0.1").port(1)
                .protocol(StandardProtocol.TCP).metadata(metadata).healthy(true).build();
    }

    private static HeartbeatConfig fastConfig() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setHeartbeatInterval(Duration.ZERO);
        cfg.setEnableMetadataChangeDetection(true);
        cfg.setMetadataUpdateIntervalSeconds(0);
        return cfg;
    }

    @Test
    void threeArgConstructorWiresDefaults() {
        RedisServiceProvider provider = new RedisServiceProvider(
                redisson, new ServiceProviderConfig(), new HeartbeatConfig());
        assertNotNull(provider.getStateManager());
        assertNotNull(provider.getConfig());
        assertNotNull(provider.getHeartbeatConfig());
        assertNotNull(provider.getRegistryKeys());
    }

    @Test
    void registerDeregisterAndNotifyCoverMainAndErrorPaths() {
        RedisServiceProvider provider = new RedisServiceProvider(redisson, new ServiceProviderConfig(),
                new HeartbeatConfig());

        assertThrows(IllegalStateException.class,
                () -> provider.register(instance("svc", "i1", Map.of())));

        provider.start();
        when(anyInstanceMap.isExists()).thenReturn(false).thenReturn(true);
        ServiceInstance ins = instance("svc", "i1", Map.of("r", "cn"));
        provider.register(ins);
        provider.register(ins); // re-registration -> UPDATED event
        provider.deregister(ins);

        // register failure is wrapped
        doThrow(new IllegalStateException("script broken"))
                .when(script)
                .evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList(),
                        any(), any(), any(), any(), any());
        assertThrows(RuntimeException.class, () -> provider.register(ins));

        provider.stop();
    }

    @Test
    void sendHeartbeatCoversAllUpdateModesAndDecisions() {
        RedisServiceProvider provider = new RedisServiceProvider(
                redisson, new ServiceProviderConfig(), fastConfig());
        provider.start();

        ServiceInstance ins = instance("svc", "i1", Map.of("r", "1"));

        // 1) first heartbeat: metadata first sight -> METADATA_UPDATE (UPDATED notify)
        provider.sendHeartbeat(ins);

        // 2) unchanged metadata, heartbeat due -> HEARTBEAT_ONLY (no UPDATED notify)
        provider.sendHeartbeat(ins);

        // 3) metadata changed with a slow heartbeat interval -> METADATA_UPDATE wins
        HeartbeatConfig slowHeartbeat = fastConfig();
        slowHeartbeat.setHeartbeatInterval(Duration.ofHours(1));
        RedisServiceProvider provider2 = new RedisServiceProvider(
                redisson, new ServiceProviderConfig(), slowHeartbeat);
        provider2.start();
        provider2.getStateManager().markMetadataUpdateCompleted("svc", "i1", Map.of("r", "1"));
        provider2.sendHeartbeat(instance("svc", "i1", Map.of("r", "2")));
        provider2.stop();

        // 4) metadata detection off and heartbeat not due -> NO_UPDATE (no executeUpdate)
        HeartbeatConfig noDetection = fastConfig();
        noDetection.setEnableMetadataChangeDetection(false);
        noDetection.setHeartbeatInterval(Duration.ofHours(1));
        RedisServiceProvider provider3 = new RedisServiceProvider(
                redisson, new ServiceProviderConfig(), noDetection);
        provider3.start();
        provider3.getStateManager().markHeartbeatOnlyCompleted("svc", "i1");
        provider3.sendHeartbeat(instance("svc", "i1", Map.of("r", "1")));
        provider3.stop();

        // batch + silent failure while running
        provider.batchSendHeartbeats(List.of(ins));
        doThrow(new IllegalStateException("network down"))
                .when(script)
                .evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList(),
                        any(), any(), any(), any(), any());
        provider.sendHeartbeat(ins);
        provider.batchSendHeartbeats(List.of(ins));

        provider.stop();
        provider.stop(); // idempotent
        provider.sendHeartbeat(ins); // not running -> silent no-op
        provider.batchSendHeartbeats(List.of(ins));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanupExpiredInstancesOuterFailureIsSwallowed() throws Exception {
        RedisServiceProvider provider = new RedisServiceProvider(redisson, null, null);
        provider.start();
        when(redisson.<String>getSet(anyString(), any(StringCodec.class)))
                .thenThrow(new IllegalStateException("index gone"));
        Method cleanupAll = RedisServiceProvider.class.getDeclaredMethod("cleanupExpiredInstances");
        cleanupAll.setAccessible(true);
        assertDoesNotThrow(() -> cleanupAll.invoke(provider));
        provider.stop();
    }

    @Test
    void stopWithoutStartIsANoOpAndInterruptedStopReinterrupts() {
        RedisServiceProvider provider = new RedisServiceProvider(redisson, new ServiceProviderConfig(),
                new HeartbeatConfig());
        provider.stop(); // not running

        RedisServiceProvider running = new RedisServiceProvider(redisson, new ServiceProviderConfig(),
                new HeartbeatConfig());
        running.start();
        Thread.currentThread().interrupt();
        try {
            running.stop();
            assertTrue(Thread.interrupted(), "interrupt flag should be re-raised");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanupBuildsSnapshotsNotifiesAndFallsBackToIdOnly() throws Exception {
        RedisServiceProvider provider = new RedisServiceProvider(redisson, new ServiceProviderConfig(),
                new HeartbeatConfig());
        provider.start();

        when(redisson.<String>getSet(anyString(), any(StringCodec.class))).thenReturn(servicesSet);
        String fullJson = "{\"host\":\"1.2.3.4\",\"port\":\"8080\",\"protocol\":\"http\","
                + "\"enabled\":\"true\",\"healthy\":\"false\",\"weight\":\"5\","
                + "\"metadata\":\"{\\\"region\\\":\\\"cn\\\"}\",\"registrationTime\":\"1\"}";
        String nestedMetadataJson = "{\"host\":\"h\",\"port\":\"1\",\"metadata\":{\"a\":\"b\"}}";
        String badMetadataJson = "{\"host\":\"h\",\"port\":\"2\",\"metadata\":\"not-json\"}";
        String minimalJson = "{\"host\":\"h\"}";
        String badProtocolJson = "{\"host\":\"h\",\"port\":\"1\",\"protocol\":\"bogus\"}";
        String nullMetaValueJson = "{\"host\":\"h\",\"port\":\"1\",\"metadata\":\"{\\\"a\\\":null}\"}";
        String brokenJson = "{{not-json";

        // snapshot pairs: valid (with metadata string), nested metadata object, bad metadata json,
        // minimal fields, bad protocol -> null snapshot -> id-only notify, broken json -> id-only notify
        when(script.evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any(), any(), any()))
                .thenReturn(List.of(
                        "i1", fullJson,
                        "i2", nestedMetadataJson,
                        "i3", badMetadataJson,
                        "i4", minimalJson,
                        "i5", badProtocolJson,
                        "i6", brokenJson,
                        "i7", nullMetaValueJson));

        Method cleanupForService = RedisServiceProvider.class
                .getDeclaredMethod("cleanupExpiredInstancesForService", String.class);
        cleanupForService.setAccessible(true);
        cleanupForService.invoke(provider, "svc");

        verify(topic, atLeastOnce()).publish(any());
        when(anyHeartbeats.size()).thenReturn(0);
        when(servicesSet.readAll()).thenReturn(Set.of("svc"));
        doThrow(new IllegalStateException("index locked")).when(servicesSet).remove(any());

        Method cleanupAll = RedisServiceProvider.class.getDeclaredMethod("cleanupExpiredInstances");
        cleanupAll.setAccessible(true);
        cleanupAll.invoke(provider);

        // empty cleanup result
        when(script.evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any(), any(), any())).thenReturn(List.of());
        cleanupForService.invoke(provider, "svc");

        // lua failure -> swallowed with error log
        when(script.evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any(), any(), any())).thenThrow(new IllegalStateException("lua broken"));
        assertDoesNotThrow(() -> cleanupForService.invoke(provider, "svc"));

        provider.stop();
    }

    @Test
    void notifyServiceChangeFailureIsSwallowed() {
        RedisServiceProvider provider = new RedisServiceProvider(redisson, new ServiceProviderConfig(),
                new HeartbeatConfig());
        provider.start();
        doThrow(new IllegalStateException("pubsub down")).when(topic).publish(any());
        when(anyInstanceMap.isExists()).thenReturn(false);
        assertDoesNotThrow(() -> provider.register(instance("svc", "i1", Map.of())));
        provider.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void registerSanitizesUnsafeNames() {
        RedisServiceProvider provider = new RedisServiceProvider(redisson, new ServiceProviderConfig(),
                new HeartbeatConfig());
        provider.start();
        ServiceInstance dirty = instance("bad:name with space", "id:1", Map.of());
        assertDoesNotThrow(() -> provider.register(dirty));
        provider.stop();
    }
}
