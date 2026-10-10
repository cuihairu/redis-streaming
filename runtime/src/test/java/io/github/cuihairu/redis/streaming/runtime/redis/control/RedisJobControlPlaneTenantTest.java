package io.github.cuihairu.redis.streaming.runtime.redis.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tenant segmentation of the control plane (docs/Multi-Tenancy-Design.md step 3):
 * per-tenant spec/version/status/hash spaces, tenant-qualified reads, tenant
 * immutability, the tenants index set, capacity quota and the authorization
 * tenant hook. The default tenant keeps the legacy key layout.
 */
class RedisJobControlPlaneTenantTest {

    private static final String PREFIX = "cp-tenant:";
    private static final String TENANT = "acme";

    private RedissonClient client;
    private RMap<String, String> defaultJobs;
    private RMap<String, String> tenantJobs;
    private RMap<String, String> defaultVersions;
    private RMap<String, String> tenantVersions;
    private RMap<String, String> statusMap;
    private RList<String> historyList;
    private RStream<String, String> auditStream;
    private RScript script;
    private RSet<String> tenantsSet;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(RedissonClient.class);
        defaultJobs = mock(RMap.class);
        tenantJobs = mock(RMap.class);
        defaultVersions = mock(RMap.class);
        tenantVersions = mock(RMap.class);
        statusMap = mock(RMap.class);
        historyList = mock(RList.class);
        auditStream = mock(RStream.class);
        script = mock(RScript.class);
        tenantsSet = mock(RSet.class);

        // catch-all first: later eq() stubs win for their exact keys
        when(client.<String, String>getMap(anyString(), any(Codec.class))).thenReturn(mock(RMap.class));
        when(client.<String, String>getMap(eq(PREFIX + "jobs"), any(Codec.class))).thenReturn(defaultJobs);
        when(client.<String, String>getMap(eq(PREFIX + TENANT + ":jobs"), any(Codec.class))).thenReturn(tenantJobs);
        when(client.<String, String>getMap(eq(PREFIX + "versions"), any(Codec.class))).thenReturn(defaultVersions);
        when(client.<String, String>getMap(eq(PREFIX + TENANT + ":versions"), any(Codec.class))).thenReturn(tenantVersions);
        when(client.<String, String>getMap(startsWith(PREFIX + "status:"), any(Codec.class))).thenReturn(statusMap);
        when(client.<String>getList(anyString(), any(Codec.class))).thenReturn(historyList);
        when(client.<String, String>getStream(eq(PREFIX + "audit"), any(Codec.class))).thenReturn(auditStream);
        when(client.getScript(any(Codec.class))).thenReturn(script);
        when(client.<String>getSet(eq(PREFIX + "tenants"), any(Codec.class))).thenReturn(tenantsSet);
        when(tenantsSet.readAll()).thenReturn(Set.of(TENANT));
    }

    private RedisJobControlPlane cp() {
        return new RedisJobControlPlane(client, PREFIX, ControlPlaneAuthorizer.allowAll(), 100, 5);
    }

    private RedisJobControlPlane cp(TenantQuotaPolicy quota) {
        return new RedisJobControlPlane(client, PREFIX, ControlPlaneAuthorizer.allowAll(), 100, 5, quota);
    }

    private JobSpec spec(String name, String tenant, int parallelism) {
        return JobSpec.builder()
                .jobName(name)
                .tenant(tenant)
                .pipelineFactory("factory")
                .config(Map.of("k", "v"))
                .parallelism(parallelism)
                .description("d")
                .build();
    }

    private String json(JobSpec spec) {
        try {
            return mapper.writeValueAsString(spec);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ===== submit =====

    @Test
    void submitStoresUnderTenantKeyspace() {
        JobSpec stored = cp().submit(spec("j1", TENANT, 2), "alice");

        assertEquals(TENANT, stored.getTenant());
        assertEquals(1L, stored.getVersion());
        verify(tenantJobs).putIfAbsent(eq("j1"), anyString());
        verify(tenantVersions).put(eq("j1"), eq("1"));
        verify(tenantsSet).add(TENANT);
        verify(statusMap).putAll(argThatHasState(JobState.PENDING_DEPLOY.name()));
        verify(auditStream).add(any());
    }

    @Test
    void submitDefaultTenantKeepsLegacyLayout() {
        cp().submit(spec("j1", "default", 1), "alice");

        verify(defaultJobs).putIfAbsent(eq("j1"), anyString());
        verify(defaultVersions).put(eq("j1"), eq("1"));
        // the default tenant is not recorded in the tenants index
        verify(tenantsSet, never()).add(anyString());
    }

    @Test
    void submitNormalizesBlankTenant() {
        JobSpec stored = cp().submit(spec("j1", null, 1), "alice");
        assertEquals("default", stored.getTenant());
        verify(defaultJobs).putIfAbsent(eq("j1"), anyString());
    }

    @Test
    void submitRejectsInvalidTenant() {
        assertThrows(IllegalArgumentException.class, () -> cp().submit(spec("j1", "bad:name", 1), "alice"));
    }

    // ===== get / list =====

    @Test
    void getResolvesDefaultFirstThenIndexedTenants() {
        when(defaultJobs.get("same")).thenReturn(json(spec("same", "default", 1)));
        when(tenantJobs.get("same")).thenReturn(json(spec("same", TENANT, 1)));

        assertEquals("default", cp().get("same").getTenant());
        assertEquals("same", cp().get(TENANT, "same").getJobName());
    }

    @Test
    void tenantQualifiedGetReadsOnlyThatTenantsStore() {
        when(tenantJobs.get("j1")).thenReturn(json(spec("j1", TENANT, 1)));

        assertEquals(TENANT, cp().get(TENANT, "j1").getTenant());
        assertNull(cp().get("other", "j1"));
    }

    @Test
    void listReturnsAllTenantsSortedByName() {
        when(defaultJobs.readAllMap()).thenReturn(Map.of("z", json(spec("z", "default", 1))));
        when(tenantJobs.readAllMap()).thenReturn(Map.of("a", json(spec("a", TENANT, 1))));

        List<JobSpec> all = cp().list();

        assertEquals(2, all.size());
        assertEquals("a", all.get(0).getJobName());
        assertEquals("z", all.get(1).getJobName());
        assertEquals(TENANT, all.get(0).getTenant());
    }

    // ===== upgrade =====

    @Test
    @SuppressWarnings("unchecked")
    void upgradeWritesUnderTenantKeyspace() {
        when(tenantJobs.get("j1")).thenReturn(json(spec("j1", TENANT, 1)));
        when(script.eval(any(), anyString(), any(),
                anyList(), any(), any(), any(), any(), any(), any())).thenReturn(2L);

        JobSpec stored = cp().upgrade("j1", s -> {
            s.setParallelism(3);
            return s;
        }, "alice");

        assertEquals(TENANT, stored.getTenant());
        assertEquals(3, stored.getParallelism());
        ArgumentCaptor<List<Object>> keys = ArgumentCaptor.forClass(List.class);
        verify(script).eval(any(), anyString(), any(),
                keys.capture(), any(), any(), any(), any(), any(), any());
        List<Object> written = keys.getValue();
        assertEquals(PREFIX + TENANT + ":versions", written.get(0));
        assertEquals(PREFIX + TENANT + ":jobs", written.get(1));
        assertEquals(PREFIX + "history:" + TENANT + ":j1", written.get(2));
        verify(auditStream).add(any());
    }

    @Test
    void upgradeRejectsTenantChange() {
        when(tenantJobs.get("j1")).thenReturn(json(spec("j1", TENANT, 1)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> cp().upgrade("j1", s -> {
                    s.setTenant("other");
                    return s;
                }, "alice"));
        assertTrue(ex.getMessage().contains("tenant"));
        verify(script, never()).eval(any(), anyString(), any(), anyList(), any(), any(), any(), any(), any(), any());
    }

    // ===== rollback =====

    @Test
    @SuppressWarnings("unchecked")
    void rollbackKeepsTenantAndUsesTenantHashes() {
        JobSpec v1 = spec("j1", TENANT, 1);
        when(tenantJobs.get("j1")).thenReturn(json(spec("j1", TENANT, 2)));
        when(historyList.isEmpty()).thenReturn(false);
        when(historyList.size()).thenReturn(1);
        when(historyList.get(0)).thenReturn(json(v1));
        when(script.eval(any(), anyString(), any(),
                anyList(), any(), any(), any(), any(), any(), any())).thenReturn(2L);

        JobSpec rolled = cp().rollback("j1", "alice");

        assertEquals(TENANT, rolled.getTenant());
        ArgumentCaptor<List<Object>> keys = ArgumentCaptor.forClass(List.class);
        verify(script).eval(any(), anyString(), any(),
                keys.capture(), any(), any(), any(), any(), any(), any());
        assertEquals(PREFIX + TENANT + ":versions", keys.getValue().get(0));
    }

    // ===== status =====

    @Test
    void tenantQualifiedStatusReadsAndWritesTenantKeys() {
        when(statusMap.readAllMap()).thenReturn(Map.of(
                "state", JobState.RUNNING.name(),
                "instanceId", "i1",
                "detail", "",
                "updatedAt", "0"));

        JobStatus st = cp().status(TENANT, "j1");
        assertNotNull(st);
        assertEquals(JobState.RUNNING, st.getState());

        cp().reportStatus(TENANT, "j1", JobState.RUNNING, "i1", null);
        verify(statusMap).putAll(argThatHasState(JobState.RUNNING.name()));
    }

    @Test
    void nameQualifiedStatusResolvesTenantFromSpec() {
        when(defaultJobs.get("j1")).thenReturn(null);
        when(tenantJobs.get("j1")).thenReturn(json(spec("j1", TENANT, 1)));
        when(statusMap.readAllMap()).thenReturn(Map.of());

        assertNull(cp().status("j1"));
        verify(client, never()).<String, String>getMap(eq(PREFIX + "status:j1"), any(Codec.class));
    }

    // ===== quota =====

    @Test
    void quotaBlocksSubmitWhenTenantJobLimitReached() {
        when(tenantJobs.readAllMap()).thenReturn(Map.of("j1", json(spec("j1", TENANT, 1))));
        RedisJobControlPlane cp = cp(TenantQuotaPolicy.builder().maxJobsPerTenant(1).build());

        assertThrows(IllegalStateException.class, () -> cp.submit(spec("j2", TENANT, 1), "alice"));
        verify(tenantJobs, never()).putIfAbsent(eq("j2"), anyString());
    }

    @Test
    void quotaBlocksSubmitWhenParallelismBudgetExhausted() {
        when(tenantJobs.readAllMap()).thenReturn(Map.of("j1", json(spec("j1", TENANT, 2))));
        RedisJobControlPlane cp = cp(TenantQuotaPolicy.builder().maxTotalParallelismPerTenant(2).build());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> cp.submit(spec("j2", TENANT, 3), "alice"));
        assertTrue(ex.getMessage().contains("maxTotalParallelismPerTenant"));
    }

    @Test
    void quotaBlocksUpgradeThatExceedsParallelismBudget() {
        when(tenantJobs.get("j1")).thenReturn(json(spec("j1", TENANT, 2)));
        when(tenantJobs.readAllMap()).thenReturn(Map.of("j1", json(spec("j1", TENANT, 2))));
        RedisJobControlPlane cp = cp(TenantQuotaPolicy.builder().maxTotalParallelismPerTenant(3).build());

        assertThrows(IllegalStateException.class,
                () -> cp.upgrade("j1", s -> {
                    s.setParallelism(4);
                    return s;
                }, "alice"));
        verify(script, never()).eval(any(), anyString(), any(), anyList(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void quotaAllowsWithinBudget() {
        when(tenantJobs.readAllMap()).thenReturn(Map.of());
        RedisJobControlPlane cp = cp(TenantQuotaPolicy.builder()
                .maxJobsPerTenant(2)
                .maxTotalParallelismPerTenant(10)
                .build());

        JobSpec stored = cp.submit(spec("j1", TENANT, 4), "alice");
        assertEquals(4, stored.getParallelism());
        verify(tenantJobs).putIfAbsent(eq("j1"), anyString());
    }

    @Test
    void noQuotaKeepsOldBehavior() {
        when(tenantJobs.readAllMap()).thenThrow(new IllegalStateException("should not be touched"));
        RedisJobControlPlane cp = cp(TenantQuotaPolicy.none());

        assertDoesNotThrow(() -> cp.submit(spec("j1", TENANT, 1), "alice"));
    }

    // ===== authorization / audit =====

    @Test
    void authorizerReceivesTenantAndDenialIsAudited() {
        RecordingAuthorizer authorizer = new RecordingAuthorizer();
        when(defaultJobs.get("j1")).thenReturn(null);
        when(tenantJobs.get("j1")).thenReturn(json(spec("j1", TENANT, 1)));
        RedisJobControlPlane cp = new RedisJobControlPlane(client, PREFIX, authorizer, 100, 5);

        assertThrows(ControlPlaneAccessDeniedException.class, () -> cp.stop("j1", "alice"));

        assertEquals(TENANT, authorizer.seenTenant);
        assertEquals(JobControlOp.STOP, authorizer.seenOp);
        assertEquals("alice", authorizer.seenActor);
        verify(auditStream).add(any());
    }

    @Test
    void tenantFieldOfAuditEntryIsParsedBack() {
        when(script.eval(any(), anyString(), any(),
                anyList(), any())).thenReturn(List.of(List.of(
                "123-0", List.of("ts", "123", "tenant", TENANT, "actor", "a",
                        "op", JobControlOp.SUBMIT.name(), "jobName", "j1", "allowed", "true", "detail", ""))));

        List<AuditEntry> entries = cp().tailAudit(10);

        assertEquals(1, entries.size());
        assertEquals(TENANT, entries.get(0).getTenant());
    }

    // ===== helpers =====

    private Map<String, String> argThatHasState(String state) {
        return ArgumentMatchers.argThat(m -> m != null && state.equals(m.get("state")));
    }

    /** Captures the tenant-aware authorize call; denies everything. */
    private static final class RecordingAuthorizer implements ControlPlaneAuthorizer {
        String seenTenant;
        String seenActor;
        JobControlOp seenOp;

        @Override
        public void authorize(String actor, JobControlOp op, String jobName) {
            throw new ControlPlaneAccessDeniedException("denied " + actor);
        }

        @Override
        public void authorize(String tenant, String actor, JobControlOp op, String jobName)
                throws ControlPlaneAccessDeniedException {
            this.seenTenant = tenant;
            this.seenActor = actor;
            this.seenOp = op;
            throw new ControlPlaneAccessDeniedException("denied " + tenant + "/" + actor);
        }
    }
}
