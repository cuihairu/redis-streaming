package io.github.cuihairu.redis.streaming.runtime.redis.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.StringCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Redis-backed {@link JobControlPlane}.
 *
 * <p>Storage layout (all under the configurable prefix, default
 * {@code streaming:runtime:control:}):</p>
 * <ul>
 *   <li>{@code jobs} hash: jobName → {@link JobSpec} JSON</li>
 *   <li>{@code versions} hash: jobName → monotonic version (CAS token)</li>
 *   <li>{@code history:<job>} list: previous spec JSON versions, newest last (cap {@code historyMaxEntries})</li>
 *   <li>{@code status:<job>} hash: observed status fields</li>
 *   <li>{@code audit} stream: audit entries, trimmed to {@code auditMaxEntries}</li>
 * </ul>
 *
 * <p>Upgrade and rollback are compare-and-set on the version hash via a single Lua
 * script (read version → compare → write spec + bump version + append history in
 * one atomic step); concurrent writers are rejected instead of silently overwriting.
 * Audit writes are best-effort: a Redis audit failure never breaks an operation.</p>
 */
@Slf4j
public class RedisJobControlPlane implements JobControlPlane {

    private static final String DEFAULT_PREFIX = "streaming:runtime:control:";

    private static final String CAS_VERSION_AND_WRITE_LUA =
            "local cur = redis.call('HGET', KEYS[1], ARGV[1]) " +
            "if not cur then return -1 end " +
            "if tostring(cur) ~= ARGV[2] then return -2 end " +
            "redis.call('HSET', KEYS[2], ARGV[1], ARGV[3]) " +
            "redis.call('HSET', KEYS[1], ARGV[1], ARGV[4]) " +
            "redis.call('RPUSH', KEYS[3], ARGV[5]) " +
            "redis.call('LTRIM', KEYS[3], -tonumber(ARGV[6]), -1) " +
            "return tonumber(ARGV[4])";

    private static final String TRIM_AUDIT_LUA =
            "return redis.call('XTRIM', KEYS[1], 'MAXLEN', '~', ARGV[1])";

    private static final String READ_AUDIT_REVERSED_LUA =
            "return redis.call('XREVRANGE', KEYS[1], '+', '-', 'COUNT', tonumber(ARGV[1]))";

    private final RedissonClient redissonClient;
    private final String prefix;
    private final ControlPlaneAuthorizer authorizer;
    private final int auditMaxEntries;
    private final int historyMaxEntries;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final String jobsKey;
    private final String versionsKey;
    private final String auditKey;

    public RedisJobControlPlane(RedissonClient redissonClient) {
        this(redissonClient, DEFAULT_PREFIX, ControlPlaneAuthorizer.allowAll(), 1000, 10);
    }

    public RedisJobControlPlane(RedissonClient redissonClient, String prefix,
                                ControlPlaneAuthorizer authorizer, int auditMaxEntries, int historyMaxEntries) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.authorizer = authorizer == null ? ControlPlaneAuthorizer.allowAll() : authorizer;
        this.auditMaxEntries = Math.max(1, auditMaxEntries);
        this.historyMaxEntries = Math.max(1, historyMaxEntries);
        this.jobsKey = prefix + "jobs";
        this.versionsKey = prefix + "versions";
        this.auditKey = prefix + "audit";
    }

    @Override
    public JobSpec submit(JobSpec spec, String actor) {
        validate(spec);
        String name = spec.getJobName();
        String resolvedActor = resolveActor(actor);
        authorize(resolvedActor, JobControlOp.SUBMIT, name);
        JobSpec copy = toBuilder(spec);
        copy.setVersion(1L);
        copy.setUpdatedBy(resolvedActor);
        copy.setUpdatedAt(System.currentTimeMillis());
        copy.setSpecHash(specHash(copy));
        String json = writeJson(copy);
        RMap<String, String> jobs = jobs();
        String previous = jobs.putIfAbsent(name, json);
        if (previous != null) {
            throw new IllegalArgumentException("Job already exists: " + name);
        }
        versions().put(name, "1");
        writeStatus(name, JobState.PENDING_DEPLOY, "", "", System.currentTimeMillis());
        appendAudit(JobControlOp.SUBMIT, resolvedActor, name, null, 1L, true, null);
        return copy;
    }

    @Override
    public JobSpec get(String jobName) {
        String json = jobs().get(jobName);
        return json == null ? null : readJson(json);
    }

    @Override
    public List<JobSpec> list() {
        Map<String, String> all = jobs().readAllMap();
        List<JobSpec> out = new ArrayList<>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            out.add(readJson(e.getValue()));
        }
        out.sort(Comparator.comparing(JobSpec::getJobName));
        return out;
    }

    @Override
    public JobSpec upgrade(String jobName, UnaryOperator<JobSpec> mutator, String actor) {
        String resolvedActor = resolveActor(actor);
        authorize(resolvedActor, JobControlOp.UPGRADE, jobName);
        JobSpec current = get(jobName);
        if (current == null) {
            throw new IllegalArgumentException("Job does not exist: " + jobName);
        }
        // snapshot the pristine spec BEFORE the mutator runs — mutators mutate in place,
        // and the CAS history entry must hold the pre-upgrade state
        String oldJson = writeJson(current);
        JobSpec next = mutator.apply(current);
        validate(next);
        if (!next.getJobName().equals(jobName)) {
            throw new IllegalArgumentException("Mutator must not change the job name");
        }
        JobSpec stored = toBuilder(next);
        stored.setVersion(current.getVersion() + 1L);
        stored.setUpdatedBy(resolvedActor);
        stored.setUpdatedAt(System.currentTimeMillis());
        stored.setSpecHash(specHash(stored));
        String newJson = writeJson(stored);
        long applied = casVersionAndWrite(jobName, current.getVersion(), newJson, oldJson);
        if (applied < 0) {
            throw new IllegalStateException("Concurrent modification on job " + jobName + " (code " + applied + "); retry the upgrade");
        }
        appendAudit(JobControlOp.UPGRADE, resolvedActor, jobName, current.getVersion(), stored.getVersion(), true, null);
        return stored;
    }

    @Override
    public JobSpec rollback(String jobName, String actor) {
        String resolvedActor = resolveActor(actor);
        authorize(resolvedActor, JobControlOp.ROLLBACK, jobName);
        JobSpec current = get(jobName);
        if (current == null) {
            throw new IllegalArgumentException("Job does not exist: " + jobName);
        }
        List<String> history = historyList(jobName);
        if (history.isEmpty()) {
            throw new IllegalStateException("No history to roll back to for job " + jobName);
        }
        String prevJson = history.get(history.size() - 1);
        JobSpec prev = readJson(prevJson);
        JobSpec stored = toBuilder(prev);
        stored.setVersion(current.getVersion() + 1L);
        stored.setUpdatedBy(resolvedActor);
        stored.setUpdatedAt(System.currentTimeMillis());
        stored.setSpecHash(specHash(stored));
        String newJson = writeJson(stored);
        long applied = casVersionAndWrite(jobName, current.getVersion(), newJson, writeJson(current));
        if (applied < 0) {
            throw new IllegalStateException("Concurrent modification on job " + jobName + " (code " + applied + "); retry the rollback");
        }
        appendAudit(JobControlOp.ROLLBACK, resolvedActor, jobName, current.getVersion(), stored.getVersion(), true, null);
        return stored;
    }

    @Override
    public void stop(String jobName, String actor) {
        String resolvedActor = resolveActor(actor);
        authorize(resolvedActor, JobControlOp.STOP, jobName);
        if (get(jobName) == null) {
            throw new IllegalArgumentException("Job does not exist: " + jobName);
        }
        JobStatus current = status(jobName);
        String instanceId = current != null && current.getInstanceId() != null ? current.getInstanceId() : "";
        String detail = current != null && current.getDetail() != null ? current.getDetail() : "";
        writeStatus(jobName, JobState.DESIRED_STOPPED, instanceId, detail, System.currentTimeMillis());
        appendAudit(JobControlOp.STOP, resolvedActor, jobName, null, null, true, null);
    }

    @Override
    public void resume(String jobName, String actor) {
        String resolvedActor = resolveActor(actor);
        authorize(resolvedActor, JobControlOp.RESUME, jobName);
        if (get(jobName) == null) {
            throw new IllegalArgumentException("Job does not exist: " + jobName);
        }
        writeStatus(jobName, JobState.PENDING_DEPLOY, "", "", System.currentTimeMillis());
        appendAudit(JobControlOp.RESUME, resolvedActor, jobName, null, null, true, null);
    }

    @Override
    public JobStatus status(String jobName) {
        RMap<String, String> status = redissonClient.<String, String>getMap(statusKey(jobName), StringCodec.INSTANCE);
        Map<String, String> all = status.readAllMap();
        if (all.isEmpty()) {
            return null;
        }
        JobStatus out = new JobStatus();
        out.setState(JobState.valueOf(all.get("state")));
        out.setInstanceId(nullToEmpty(all.get("instanceId")));
        out.setDetail(nullToEmpty(all.get("detail")));
        out.setUpdatedAt(Long.parseLong(all.getOrDefault("updatedAt", "0")));
        return out;
    }

    @Override
    public void reportStatus(String jobName, JobState state, String instanceId, String detail) {
        writeStatus(jobName, state, instanceId, detail, System.currentTimeMillis());
        if (state == JobState.FAILED) {
            appendAudit(JobControlOp.REPORT_STATUS, "agent", jobName, null, null, true, detail);
        }
    }

    @Override
    public List<AuditEntry> tailAudit(int limit) {
        int clamped = Math.max(1, Math.min(limit, 10000));
        try {
            RScript script = redissonClient.getScript(StringCodec.INSTANCE);
            @SuppressWarnings("unchecked")
            List<Object> rows = script.eval(RScript.Mode.READ_ONLY, READ_AUDIT_REVERSED_LUA,
                    RScript.ReturnType.LIST, List.of(auditKey), String.valueOf(clamped));
            List<AuditEntry> out = new ArrayList<>();
            if (rows != null) {
                for (Object row : rows) {
                    if (!(row instanceof List)) {
                        continue;
                    }
                    List<?> pair = (List<?>) row;
                    if (pair.size() < 2) {
                        continue;
                    }
                    Object fields = pair.get(1);
                    if (!(fields instanceof List)) {
                        continue;
                    }
                    List<?> list = (List<?>) fields;
                    Map<String, Object> fieldMap = new LinkedHashMap<>();
                    for (int idx = 0; idx + 1 < list.size(); idx += 2) {
                        Object k = list.get(idx);
                        Object v = list.get(idx + 1);
                        if (k != null && v != null) {
                            fieldMap.put(String.valueOf(k), String.valueOf(v));
                        }
                    }
                    out.add(parseAuditFields(fieldMap));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to read audit stream", e);
            return List.of();
        }
    }

    private void validate(JobSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("Spec cannot be null");
        }
        if (spec.getJobName() == null || spec.getJobName().isBlank()) {
            throw new IllegalArgumentException("Job name cannot be blank");
        }
        if (spec.getPipelineFactory() == null || spec.getPipelineFactory().isBlank()) {
            throw new IllegalArgumentException("Pipeline factory cannot be blank");
        }
        if (spec.getParallelism() < 1) {
            throw new IllegalArgumentException("Parallelism must be >= 1");
        }
        if (spec.getConfig() == null) {
            throw new IllegalArgumentException("Config cannot be null");
        }
    }

    private void authorize(String actor, JobControlOp op, String jobName) {
        try {
            authorizer.authorize(actor, op, jobName);
        } catch (ControlPlaneAccessDeniedException e) {
            appendAudit(op, actor, jobName, null, null, false, e.getMessage());
            throw e;
        }
    }

    private JobSpec toBuilder(JobSpec spec) {
        Map<String, String> config = spec.getConfig() == null ? Map.of() : new LinkedHashMap<>(spec.getConfig());
        return JobSpec.builder()
                .jobName(spec.getJobName())
                .pipelineFactory(spec.getPipelineFactory())
                .config(config)
                .parallelism(spec.getParallelism())
                .version(spec.getVersion())
                .description(spec.getDescription())
                .updatedBy(spec.getUpdatedBy())
                .updatedAt(spec.getUpdatedAt())
                .specHash(spec.getSpecHash())
                .build();
    }

    private String specHash(JobSpec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append(spec.getJobName()).append('|')
                .append(spec.getPipelineFactory()).append('|');
        spec.getConfig().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append(';'));
        sb.append('|').append(spec.getParallelism()).append('|')
                .append(spec.getDescription() == null ? "" : spec.getDescription());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String resolveActor(String actor) {
        if (actor == null || actor.isBlank()) {
            return System.getProperty("user.name", "unknown");
        }
        return actor;
    }

    /** @return the configured key prefix (agents derive their claim key prefix from it). */
    public String prefix() {
        return prefix;
    }

    private RMap<String, String> jobs() {
        return redissonClient.<String, String>getMap(jobsKey, StringCodec.INSTANCE);
    }

    private RMap<String, String> versions() {
        return redissonClient.<String, String>getMap(versionsKey, StringCodec.INSTANCE);
    }

    private RList<String> historyList(String jobName) {
        return redissonClient.<String>getList(historyKey(jobName), StringCodec.INSTANCE);
    }

    private String historyKey(String jobName) {
        return prefix + "history:" + jobName;
    }

    private String statusKey(String jobName) {
        return prefix + "status:" + jobName;
    }

    /**
     * Atomic compare-and-set on the version hash: writes the new spec JSON, bumps the
     * version and appends the previous spec JSON to the job history in one step.
     * Returns -1 when the job vanished, -2 on version conflict, and the new version
     * on success.
     */
    private long casVersionAndWrite(String jobName, long expectedVersion, String newJson, String oldJson) {
        List<Object> keys = List.of(versionsKey, jobsKey, historyKey(jobName));
        Object result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, CAS_VERSION_AND_WRITE_LUA, RScript.ReturnType.LONG, keys,
                jobName, String.valueOf(expectedVersion), newJson, String.valueOf(expectedVersion + 1),
                oldJson, String.valueOf(historyMaxEntries));
        if (result == null) {
            throw new IllegalStateException("CAS script did not return a value for job " + jobName);
        }
        return ((Number) result).longValue();
    }

    private void writeStatus(String jobName, JobState state, String instanceId, String detail, long updatedAt) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("state", state.name());
        fields.put("instanceId", nullToEmpty(instanceId));
        fields.put("detail", nullToEmpty(detail));
        fields.put("updatedAt", String.valueOf(updatedAt));
        redissonClient.<String, String>getMap(statusKey(jobName), StringCodec.INSTANCE).putAll(fields);
    }

    private void appendAudit(JobControlOp op, String actor, String jobName, Long fromVersion,
                             Long toVersion, boolean allowed, String detail) {
        try {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("ts", String.valueOf(System.currentTimeMillis()));
            entry.put("actor", nullToEmpty(actor));
            entry.put("op", op.name());
            entry.put("jobName", nullToEmpty(jobName));
            if (fromVersion != null) {
                entry.put("fromVersion", String.valueOf(fromVersion));
            }
            if (toVersion != null) {
                entry.put("toVersion", String.valueOf(toVersion));
            }
            entry.put("allowed", String.valueOf(allowed));
            entry.put("detail", nullToEmpty(detail));
            RStream<String, String> stream = redissonClient.<String, String>getStream(auditKey, StringCodec.INSTANCE);
            stream.add(StreamAddArgs.entries(entry));
            redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_ONLY, TRIM_AUDIT_LUA, RScript.ReturnType.LONG,
                    List.of(auditKey), String.valueOf(auditMaxEntries));
        } catch (Exception e) {
            log.warn("Audit write failed for op {} on job {}", op, jobName, e);
        }
    }

    private AuditEntry parseAuditFields(Map<String, Object> fields) {
        AuditEntry entry = new AuditEntry();
        entry.setTs(Long.parseLong((String) fields.getOrDefault("ts", "0")));
        entry.setActor((String) fields.get("actor"));
        entry.setOp(JobControlOp.valueOf((String) fields.get("op")));
        entry.setJobName((String) fields.get("jobName"));
        String from = (String) fields.get("fromVersion");
        entry.setFromVersion(from == null ? null : Long.valueOf(from));
        String to = (String) fields.get("toVersion");
        entry.setToVersion(to == null ? null : Long.valueOf(to));
        entry.setAllowed(Boolean.parseBoolean((String) fields.get("allowed")));
        entry.setDetail((String) fields.get("detail"));
        return entry;
    }

    private String writeJson(JobSpec spec) {
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize job spec " + spec.getJobName(), e);
        }
    }

    private JobSpec readJson(String json) {
        try {
            return objectMapper.readValue(json, JobSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse job spec JSON", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
