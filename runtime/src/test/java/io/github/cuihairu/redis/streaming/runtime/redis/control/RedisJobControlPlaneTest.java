package io.github.cuihairu.redis.streaming.runtime.redis.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link RedisJobControlPlane} logic with mocked Redis:
 * validation, authorization (denial audited then thrown), version plumbing
 * (submit=1, upgrade CAS outcomes), rollback history handling, and audit edges.
 * Lua/Redis semantics are exercised by the integration test.
 */
class RedisJobControlPlaneTest {

    private static final String PREFIX = "it-cp:";

    private RedissonClient client;
    private RMap<String, String> jobsMap;
    private RMap<String, String> versionsMap;
    private RMap<String, String> statusMap;
    private RList<String> historyList;
    private RStream<String, String> auditStream;
    private RScript script;
    private RedisJobControlPlane cp;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(RedissonClient.class);
        jobsMap = mock(RMap.class);
        versionsMap = mock(RMap.class);
        statusMap = mock(RMap.class);
        historyList = mock(RList.class);
        auditStream = mock(RStream.class);
        script = mock(RScript.class);

        when(client.<String, String>getMap(eq(PREFIX + "jobs"), any(Codec.class))).thenReturn(jobsMap);
        when(client.<String, String>getMap(eq(PREFIX + "versions"), any(Codec.class))).thenReturn(versionsMap);
        when(client.<String, String>getMap(startsWith(PREFIX + "status:"), any(Codec.class))).thenReturn(statusMap);
        when(client.<String>getList(anyString(), any(Codec.class))).thenReturn(historyList);
        when(client.<String, String>getStream(eq(PREFIX + "audit"), any(Codec.class))).thenReturn(auditStream);
        when(client.getScript(any(Codec.class))).thenReturn(script);

        cp = new RedisJobControlPlane(client, PREFIX, ControlPlaneAuthorizer.allowAll(), 100, 5);
    }

    private String json(JobSpec spec) {
        try {
            return mapper.writeValueAsString(spec);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JobSpec spec(String name, String factory, Map<String, String> config, int parallelism) {
        return JobSpec.builder()
                .jobName(name)
                .pipelineFactory(factory)
                .config(config)
                .parallelism(parallelism)
                .description("d")
                .build();
    }

    @Test
    void submitRejectsInvalidSpecs() {
        assertThrows(IllegalArgumentException.class, () -> cp.submit(null, "a"));
        assertThrows(IllegalArgumentException.class,
                () -> cp.submit(spec(" ", "f", Map.of(), 1), "a"));
        assertThrows(IllegalArgumentException.class,
                () -> cp.submit(spec("j", " ", Map.of(), 1), "a"));
        assertThrows(IllegalArgumentException.class,
                () -> cp.submit(spec("j", "f", Map.of(), 0), "a"));
        assertThrows(IllegalArgumentException.class,
                () -> cp.submit(spec("j", "f", null, 1), "a"));
    }

    @Test
    void submitStoresSpecVersionOneAndPendingStatus() {
        JobSpec stored = cp.submit(spec("j1", "factory", Map.of("k", "v"), 2), "alice");

        assertEquals(1L, stored.getVersion());
        assertEquals("alice", stored.getUpdatedBy());
        assertNotNull(stored.getSpecHash());
        assertFalse(stored.getSpecHash().isEmpty());

        verify(jobsMap).putIfAbsent(eq("j1"), anyString());
        verify(versionsMap).put(eq("j1"), eq("1"));
        verify(statusMap).putAll(argThatHasState(JobState.PENDING_DEPLOY.name()));
        verify(auditStream).add(any());
    }

    private Map<String, String> argThatHasState(String state) {
        return ArgumentMatchers.argThat(m -> m != null && state.equals(m.get("state")));
    }

    @Test
    void submitRejectsDuplicateName() {
        when(jobsMap.putIfAbsent(eq("j1"), anyString())).thenReturn("existing-json");
        assertThrows(IllegalArgumentException.class,
                () -> cp.submit(spec("j1", "f", Map.of(), 1), "a"));
    }

    @Test
    void submitDeniedAuthorizerAuditsAndThrows() {
        RedisJobControlPlane guarded = new RedisJobControlPlane(client, PREFIX,
                (actor, op, job) -> {
                    throw new ControlPlaneAccessDeniedException("no submit for " + actor);
                }, 100, 5);

        assertThrows(ControlPlaneAccessDeniedException.class,
                () -> guarded.submit(spec("j1", "f", Map.of(), 1), "eve"));
        // denial is audited before the exception surfaces
        verify(auditStream).add(any());
    }

    @Test
    void submitResolvesDefaultActor() {
        JobSpec stored = cp.submit(spec("j1", "f", Map.of(), 1), null);
        assertEquals(System.getProperty("user.name", "unknown"), stored.getUpdatedBy());
    }

    @Test
    void upgradeRejectsUnknownJob() {
        when(jobsMap.get("ghost")).thenReturn(null);
        assertThrows(IllegalArgumentException.class,
                () -> cp.upgrade("ghost", s -> s, "a"));
    }

    @Test
    void upgradeRejectsJobRenameMutator() {
        when(jobsMap.get("j1")).thenReturn(json(spec("j1", "f", Map.of(), 1)));
        assertThrows(IllegalArgumentException.class,
                () -> cp.upgrade("j1", s -> {
                    s.setJobName("other");
                    return s;
                }, "a"));
    }

    @Test
    void upgradeSurfacesConcurrentConflict() {
        when(jobsMap.get("j1")).thenReturn(json(spec("j1", "f", Map.of(), 1)));
        when(script.eval(any(), anyString(), any(),
                anyList(), any(), any(), any(), any(), any(), any())).thenReturn(-2L);
        assertThrows(IllegalStateException.class,
                () -> cp.upgrade("j1", s -> s, "a"));
    }

    @Test
    void upgradeAppliesAndReturnsNewVersion() {
        when(jobsMap.get("j1")).thenReturn(json(JobSpec.builder()
                .jobName("j1").pipelineFactory("f").config(Map.of("k", "v"))
                .parallelism(1).version(2L).build()));
        when(script.eval(any(), anyString(), any(),
                anyList(), any(), any(), any(), any(), any(), any())).thenReturn(3L);

        JobSpec upgraded = cp.upgrade("j1", s -> {
            s.setParallelism(4);
            return s;
        }, "bob");

        assertEquals(3L, upgraded.getVersion());
        assertEquals(4, upgraded.getParallelism());
        assertEquals("bob", upgraded.getUpdatedBy());
        verify(auditStream).add(any());
    }

    @Test
    void rollbackRequiresHistory() {
        when(jobsMap.get("j1")).thenReturn(json(spec("j1", "f", Map.of(), 1)));
        when(historyList.isEmpty()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> cp.rollback("j1", "a"));
    }

    @Test
    void rollbackAppliesPreviousVersionWithNewVersionNumber() {
        JobSpec v1 = spec("j1", "f", Map.of("k", "old"), 1);
        when(jobsMap.get("j1")).thenReturn(json(JobSpec.builder()
                .jobName("j1").pipelineFactory("f").config(Map.of("k", "new"))
                .parallelism(1).version(2L).build()));
        when(historyList.isEmpty()).thenReturn(false);
        when(historyList.size()).thenReturn(1);
        when(historyList.get(0)).thenReturn(json(v1));
        when(script.eval(any(), anyString(), any(),
                anyList(), any(), any(), any(), any(), any(), any())).thenReturn(3L);

        JobSpec rolled = cp.rollback("j1", "carol");

        assertEquals(3L, rolled.getVersion());
        assertEquals("old", rolled.getConfig().get("k"));
        assertEquals("carol", rolled.getUpdatedBy());
    }

    @Test
    void stopResumeRequireExistingJob() {
        when(jobsMap.get("ghost")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> cp.stop("ghost", "a"));
        assertThrows(IllegalArgumentException.class, () -> cp.resume("ghost", "a"));

        when(jobsMap.get("j1")).thenReturn(json(spec("j1", "f", Map.of(), 1)));
        cp.stop("j1", "a");
        verify(statusMap).putAll(argThatHasState(JobState.DESIRED_STOPPED.name()));
        cp.resume("j1", "a");
        verify(statusMap).putAll(argThatHasState(JobState.PENDING_DEPLOY.name()));
    }

    @Test
    void reportStatusFailureIsAudited() {
        cp.reportStatus("j1", JobState.FAILED, "inst-1", "boom");
        verify(statusMap).putAll(argThatHasState(JobState.FAILED.name()));
        verify(auditStream).add(any());
    }

    @Test
    void reportStatusSuccessIsNotAudited() {
        cp.reportStatus("j1", JobState.RUNNING, "inst-1", null);
        verify(statusMap).putAll(argThatHasState(JobState.RUNNING.name()));
        verify(auditStream, org.mockito.Mockito.never()).add(any());
    }

    @Test
    void statusParsesFieldsAndUnknownJobReturnsNull() {
        when(statusMap.readAllMap()).thenReturn(Map.of(
                "state", "RUNNING",
                "instanceId", "inst-1",
                "detail", "",
                "updatedAt", "1791593692751"));
        JobStatus st = cp.status("j1");
        assertEquals(JobState.RUNNING, st.getState());
        assertEquals("inst-1", st.getInstanceId());
        assertEquals(1791593692751L, st.getUpdatedAt());

        when(statusMap.readAllMap()).thenReturn(Map.of());
        assertNull(cp.status("ghost"));
    }

    @Test
    void listReturnsAllSpecsSortedByName() {
        when(jobsMap.readAllMap()).thenReturn(new java.util.LinkedHashMap<>(Map.of(
                "jobB", json(spec("jobB", "f", Map.of(), 1)),
                "jobA", json(spec("jobA", "f", Map.of(), 1)))));
        List<JobSpec> all = cp.list();
        assertEquals(2, all.size());
        assertEquals("jobA", all.get(0).getJobName());
        assertEquals("jobB", all.get(1).getJobName());
    }

    @Test
    void tailAuditParsesEntries() {
        // mirrors real XREVRANGE output: absent fields are simply not present
        // (no empty strings — fromVersion is omitted for submit entries)
        List<Object> fields = List.of(
                "ts", "1791593692751",
                "actor", "alice",
                "op", "SUBMIT",
                "jobName", "j1",
                "toVersion", "1",
                "allowed", "true");
        when(script.eval(any(), anyString(), any(),
                anyList(), any())).thenReturn(List.of(List.of("1791593692751-0", fields)));

        List<AuditEntry> entries = cp.tailAudit(10);
        assertEquals(1, entries.size());
        AuditEntry e = entries.get(0);
        assertEquals(1791593692751L, e.getTs());
        assertEquals("alice", e.getActor());
        assertEquals(JobControlOp.SUBMIT, e.getOp());
        assertEquals("j1", e.getJobName());
        assertNull(e.getFromVersion());
        assertEquals(Long.valueOf(1L), e.getToVersion());
        assertTrue(e.isAllowed());
    }

    @Test
    void submitAcceptsNullDescription() {
        JobSpec noDesc = JobSpec.builder()
                .jobName("j1")
                .pipelineFactory("f")
                .config(Map.of())
                .parallelism(1)
                .build();
        JobSpec stored = cp.submit(noDesc, "a");
        assertNotNull(stored.getSpecHash());
    }

    @Test
    void tailAuditReturnsEmptyOnRedisError() {
        when(script.eval(any(), anyString(), any(),
                anyList(), any())).thenThrow(new RuntimeException("down"));
        assertTrue(cp.tailAudit(5).isEmpty());
    }

    @Test
    void specHashTracksContentChanges() {
        JobSpec a = cp.submit(spec("j1", "f", Map.of("k", "v"), 1), "a");
        JobSpec b = cp.submit(spec("j2", "f", Map.of("k", "v"), 1), "a");
        JobSpec c = cp.submit(spec("j3", "f", Map.of("k", "other"), 1), "a");

        assertNotEquals(a.getSpecHash(), b.getSpecHash()); // name is part of content
        assertNotEquals(a.getSpecHash(), c.getSpecHash()); // config differs

        // hash survives the JSON round-trip through the store
        when(jobsMap.get("j1")).thenReturn(json(a));
        assertEquals(a.getSpecHash(), cp.get("j1").getSpecHash());
    }

    @Test
    void defaultConstructorUsesDefaultPrefix() {
        // the single-arg ctor constructs with the built-in prefix; submitting would hit
        // a different key prefix than the per-test mock stubs, so we pin construction only
        assertDoesNotThrow(() -> new RedisJobControlPlane(client));
    }

    @Test
    void rollbackRejectsUnknownJobAndSurfacesConflict() {
        when(jobsMap.get("ghost")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> cp.rollback("ghost", "a"));

        JobSpec v2 = JobSpec.builder()
                .jobName("j1").pipelineFactory("f").config(Map.of("k", "v"))
                .parallelism(1).version(2L).build();
        when(jobsMap.get("j1")).thenReturn(json(v2));
        when(historyList.isEmpty()).thenReturn(false);
        when(historyList.size()).thenReturn(1);
        when(historyList.get(0)).thenReturn(json(v2));
        when(script.eval(any(), anyString(), any(),
                anyList(), any(), any(), any(), any(), any(), any())).thenReturn(-2L);
        assertThrows(IllegalStateException.class, () -> cp.rollback("j1", "a"));
    }

    @Test
    void casNullResultSurfacesAsIllegalState() {
        when(jobsMap.get("j1")).thenReturn(json(JobSpec.builder()
                .jobName("j1").pipelineFactory("f").config(Map.of())
                .parallelism(1).version(1L).build()));
        when(script.eval(any(), anyString(), any(),
                anyList(), any(), any(), any(), any(), any(), any())).thenReturn(null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> cp.upgrade("j1", s -> s, "a"));
        assertTrue(e.getMessage().contains("did not return a value"));
    }

    @Test
    void auditWriteFailureIsSwallowed() {
        org.mockito.Mockito.doThrow(new RuntimeException("stream down"))
                .when(auditStream).add(any());
        JobSpec stored = cp.submit(spec("j1", "f", Map.of(), 1), "a");
        assertNotNull(stored);
        verify(jobsMap).putIfAbsent(eq("j1"), anyString());
    }

    @Test
    void tailAuditSkipsMalformedRows() {
        when(script.eval(any(), anyString(), any(),
                anyList(), any())).thenReturn(List.of(
                "not-a-row",
                List.of("only-one-element"),
                List.of("id", "fields-not-a-list"),
                List.of("1791593692751-0", List.of(
                        "ts", "1791593692751",
                        "actor", "alice",
                        "op", "STOP",
                        "jobName", "j1",
                        "allowed", "true"))));

        List<AuditEntry> entries = cp.tailAudit(10);
        assertEquals(1, entries.size());
        assertEquals(JobControlOp.STOP, entries.get(0).getOp());
    }

    @Test
    void corruptSpecJsonSurfacesAsIllegalState() {
        when(jobsMap.get("j1")).thenReturn("this is not json");
        assertThrows(IllegalStateException.class, () -> cp.get("j1"));
    }
}
