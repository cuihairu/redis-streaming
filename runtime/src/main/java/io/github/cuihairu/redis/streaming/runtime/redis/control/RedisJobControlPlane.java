package io.github.cuihairu.redis.streaming.runtime.redis.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Redis-backed {@link JobControlPlane}.
 *
 * <p>Storage layout (all under the configurable prefix, default
 * {@code streaming:runtime:control:}). Spec hashes, version hashes, job history and job
 * status are segmented by tenant ({@code {tenant}:jobs}, ...); the {@code default} tenant
 * keeps the pre-tenant layout exactly ({@code jobs}, {@code versions}, {@code history:<job>},
 * {@code status:<job>}), so existing deployments need no migration (docs/Multi-Tenancy-Design.md,
 * step 3). Non-default tenants are tracked in a {@code tenants} index set so reads and
 * listings can resolve their key space without a Redis scan:</p>
 * <ul>
 *   <li>{@code tenants} set: registered non-default tenant names</li>
 *   <li>{@code [{tenant}:]jobs} hash: jobName → {@link JobSpec} JSON</li>
 *   <li>{@code [{tenant}:]versions} hash: jobName → monotonic version (CAS token)</li>
 *   <li>{@code history:[{tenant}:]<job>} list: previous spec JSON versions, newest last (cap {@code historyMaxEntries})</li>
 *   <li>{@code status:[{tenant}:]<job>} hash: observed status fields</li>
 *   <li>{@code audit} stream: audit entries (each carries its tenant), trimmed to {@code auditMaxEntries}</li>
 * </ul>
 *
 * <p>Upgrade and rollback are compare-and-set on the version hash via a single Lua
 * script (read version → compare → write spec + bump version + append history in
 * one atomic step); concurrent writers are rejected instead of silently overwriting.
 * Job names are unique per tenant, and a job may not change tenant (that would relocate
 * state, streams and checkpoints). Audit writes are best-effort: a Redis audit failure
 * never breaks an operation.</p>
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
    private final TenantQuotaPolicy quotaPolicy;
    private final int auditMaxEntries;
    private final int historyMaxEntries;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final String tenantsKey;
    private final String auditKey;

    public RedisJobControlPlane(RedissonClient redissonClient) {
        this(redissonClient, DEFAULT_PREFIX, ControlPlaneAuthorizer.allowAll(), 1000, 10, null);
    }

    public RedisJobControlPlane(RedissonClient redissonClient, String prefix,
                                ControlPlaneAuthorizer authorizer, int auditMaxEntries, int historyMaxEntries) {
        this(redissonClient, prefix, authorizer, auditMaxEntries, historyMaxEntries, null);
    }

    /** Full constructor: authorizer plus per-tenant capacity quota (null = unlimited). */
    public RedisJobControlPlane(RedissonClient redissonClient, String prefix,
                                ControlPlaneAuthorizer authorizer, int auditMaxEntries, int historyMaxEntries,
                                TenantQuotaPolicy quotaPolicy) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.authorizer = authorizer == null ? ControlPlaneAuthorizer.allowAll() : authorizer;
        this.quotaPolicy = quotaPolicy == null ? TenantQuotaPolicy.none() : quotaPolicy;
        this.auditMaxEntries = Math.max(1, auditMaxEntries);
        this.historyMaxEntries = Math.max(1, historyMaxEntries);
        this.tenantsKey = prefix + "tenants";
        this.auditKey = prefix + "audit";
    }

    @Override
    public JobSpec submit(JobSpec spec, String actor) {
        validate(spec);
        String name = spec.getJobName();
        String tenant = StreamKeys.normalizeTenant(spec.getTenant());
        String resolvedActor = resolveActor(actor);
        authorize(tenant, resolvedActor, JobControlOp.SUBMIT, name);
        enforceQuota(tenant, name, spec.getParallelism(), spec.getParallelism());
        JobSpec copy = toBuilder(spec);
        copy.setTenant(tenant);
        copy.setVersion(1L);
        copy.setUpdatedBy(resolvedActor);
        copy.setUpdatedAt(System.currentTimeMillis());
        copy.setSpecHash(specHash(copy));
        String json = writeJson(copy);
        RMap<String, String> jobs = jobs(tenant);
        String previous = jobs.putIfAbsent(name, json);
        if (previous != null) {
            throw new IllegalArgumentException("Job already exists: " + name);
        }
        rememberTenant(tenant);
        versions(tenant).put(name, "1");
        writeStatus(tenant, name, JobState.PENDING_DEPLOY, "", "", System.currentTimeMillis());
        appendAudit(tenant, JobControlOp.SUBMIT, resolvedActor, name, null, 1L, true, null);
        return copy;
    }

    @Override
    public JobSpec get(String jobName) {
        String json = jobs(StreamKeys.DEFAULT_TENANT).get(jobName);
        if (json != null) {
            return readJson(json);
        }
        for (String tenant : knownTenants()) {
            json = jobs(tenant).get(jobName);
            if (json != null) {
                return readJson(json);
            }
        }
        return null;
    }

    @Override
    public JobSpec get(String tenant, String jobName) {
        return readOrNull(jobs(StreamKeys.normalizeTenant(tenant)).get(jobName));
    }

    @Override
    public List<JobSpec> list() {
        List<JobSpec> out = new ArrayList<>();
        for (Map.Entry<String, String> e : jobs(StreamKeys.DEFAULT_TENANT).readAllMap().entrySet()) {
            out.add(readJson(e.getValue()));
        }
        for (String tenant : knownTenants()) {
            for (Map.Entry<String, String> e : jobs(tenant).readAllMap().entrySet()) {
                out.add(readJson(e.getValue()));
            }
        }
        out.sort(Comparator.comparing(JobSpec::getJobName));
        return out;
    }

    @Override
    public JobSpec upgrade(String jobName, UnaryOperator<JobSpec> mutator, String actor) {
        String resolvedActor = resolveActor(actor);
        JobSpec current = get(jobName);
        if (current == null) {
            // authorize first so the denial is audited without leaking existence
            authorize(StreamKeys.DEFAULT_TENANT, resolvedActor, JobControlOp.UPGRADE, jobName);
            throw new IllegalArgumentException("Job does not exist: " + jobName);
        }
        String tenant = StreamKeys.normalizeTenant(current.getTenant());
        authorize(tenant, resolvedActor, JobControlOp.UPGRADE, jobName);
        // snapshot the pristine spec BEFORE the mutator runs — mutators mutate in place,
        // and the CAS history entry must hold the pre-upgrade state
        String oldJson = writeJson(current);
        int currentParallelism = current.getParallelism();
        JobSpec next = mutator.apply(current);
        validate(next);
        if (!next.getJobName().equals(jobName)) {
            throw new IllegalArgumentException("Mutator must not change the job name");
        }
        if (!tenant.equals(StreamKeys.normalizeTenant(next.getTenant()))) {
            throw new IllegalArgumentException("Mutator must not change the tenant");
        }
        JobSpec stored = toBuilder(next);
        stored.setTenant(tenant);
        stored.setVersion(current.getVersion() + 1L);
        stored.setUpdatedBy(resolvedActor);
        stored.setUpdatedAt(System.currentTimeMillis());
        stored.setSpecHash(specHash(stored));
        String newJson = writeJson(stored);
        enforceQuota(tenant, jobName, stored.getParallelism(), Math.max(0, stored.getParallelism() - currentParallelism));
        long applied = casVersionAndWrite(tenant, jobName, current.getVersion(), newJson, oldJson);
        if (applied < 0) {
            throw new IllegalStateException("Concurrent modification on job " + jobName + " (code " + applied + "); retry the upgrade");
        }
        appendAudit(tenant, JobControlOp.UPGRADE, resolvedActor, jobName, current.getVersion(), stored.getVersion(), true, null);
        return stored;
    }

    @Override
    public JobSpec rollback(String jobName, String actor) {
        String resolvedActor = resolveActor(actor);
        JobSpec current = get(jobName);
        if (current == null) {
            authorize(StreamKeys.DEFAULT_TENANT, resolvedActor, JobControlOp.ROLLBACK, jobName);
            throw new IllegalArgumentException("Job does not exist: " + jobName);
        }
        String tenant = StreamKeys.normalizeTenant(current.getTenant());
        authorize(tenant, resolvedActor, JobControlOp.ROLLBACK, jobName);
        List<String> history = historyList(tenant, jobName);
        if (history.isEmpty()) {
            throw new IllegalStateException("No history to roll back to for job " + jobName);
        }
        String prevJson = history.get(history.size() - 1);
        JobSpec prev = readJson(prevJson);
        JobSpec stored = toBuilder(prev);
        stored.setTenant(tenant);
        stored.setVersion(current.getVersion() + 1L);
        stored.setUpdatedBy(resolvedActor);
        stored.setUpdatedAt(System.currentTimeMillis());
        stored.setSpecHash(specHash(stored));
        String newJson = writeJson(stored);
        long applied = casVersionAndWrite(tenant, jobName, current.getVersion(), newJson, writeJson(current));
        if (applied < 0) {
            throw new IllegalStateException("Concurrent modification on job " + jobName + " (code " + applied + "); retry the rollback");
        }
        appendAudit(tenant, JobControlOp.ROLLBACK, resolvedActor, jobName, current.getVersion(), stored.getVersion(), true, null);
        return stored;
    }

    @Override
    public void stop(String jobName, String actor) {
        String resolvedActor = resolveActor(actor);
        JobSpec spec = get(jobName);
        if (spec == null) {
            authorize(StreamKeys.DEFAULT_TENANT, resolvedActor, JobControlOp.STOP, jobName);
            throw new IllegalArgumentException("Job does not exist: " + jobName);
        }
        String tenant = StreamKeys.normalizeTenant(spec.getTenant());
        authorize(tenant, resolvedActor, JobControlOp.STOP, jobName);
        JobStatus current = status(jobName);
        String instanceId = current != null && current.getInstanceId() != null ? current.getInstanceId() : "";
        String detail = current != null && current.getDetail() != null ? current.getDetail() : "";
        writeStatus(tenant, jobName, JobState.DESIRED_STOPPED, instanceId, detail, System.currentTimeMillis());
        appendAudit(tenant, JobControlOp.STOP, resolvedActor, jobName, null, null, true, null);
    }

    @Override
    public void resume(String jobName, String actor) {
        String resolvedActor = resolveActor(actor);
        JobSpec spec = get(jobName);
        if (spec == null) {
            authorize(StreamKeys.DEFAULT_TENANT, resolvedActor, JobControlOp.RESUME, jobName);
            throw new IllegalArgumentException("Job does not exist: " + jobName);
        }
        String tenant = StreamKeys.normalizeTenant(spec.getTenant());
        authorize(tenant, resolvedActor, JobControlOp.RESUME, jobName);
        writeStatus(tenant, jobName, JobState.PENDING_DEPLOY, "", "", System.currentTimeMillis());
        appendAudit(tenant, JobControlOp.RESUME, resolvedActor, jobName, null, null, true, null);
    }

    @Override
    public JobStatus status(String jobName) {
        JobSpec spec = get(jobName);
        String tenant = spec != null ? StreamKeys.normalizeTenant(spec.getTenant()) : StreamKeys.DEFAULT_TENANT;
        return status(tenant, jobName);
    }

    @Override
    public JobStatus status(String tenant, String jobName) {
        RMap<String, String> status = redissonClient.<String, String>getMap(
                statusKey(StreamKeys.normalizeTenant(tenant), jobName), StringCodec.INSTANCE);
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
        JobSpec spec = get(jobName);
        String tenant = spec != null ? StreamKeys.normalizeTenant(spec.getTenant()) : StreamKeys.DEFAULT_TENANT;
        reportStatus(tenant, jobName, state, instanceId, detail);
    }

    @Override
    public void reportStatus(String tenant, String jobName, JobState state, String instanceId, String detail) {
        String t = StreamKeys.normalizeTenant(tenant);
        writeStatus(t, jobName, state, instanceId, detail, System.currentTimeMillis());
        if (state == JobState.FAILED) {
            appendAudit(t, JobControlOp.REPORT_STATUS, "agent", jobName, null, null, true, detail);
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
        // blank/null collapses to "default" (no key segment); anything else must be a
        // valid name so derived keys stay unambiguous
        spec.setTenant(StreamKeys.normalizeTenant(spec.getTenant()));
    }

    private void authorize(String tenant, String actor, JobControlOp op, String jobName) {
        try {
            authorizer.authorize(tenant, actor, op, jobName);
        } catch (ControlPlaneAccessDeniedException e) {
            appendAudit(tenant, op, actor, jobName, null, null, false, e.getMessage());
            throw e;
        }
    }

    /**
     * Enforce the per-tenant capacity quota. The job count and parallelism sum are read
     * from the stored specs of the tenant, so the quota can never drift from the actual
     * state (no separate counter to fall out of sync). A limit of 0 is unlimited.
     *
     * @param tenant         tenant being checked
     * @param jobName        job being written (used in the error message)
     * @param parallelism    parallelism of the spec being written
     * @param parallelismDelta added parallelism vs. the currently stored spec (0 on submit)
     */
    private void enforceQuota(String tenant, String jobName, int parallelism, int parallelismDelta) {
        int maxJobs = quotaPolicy.getMaxJobsPerTenant();
        int maxParallelism = quotaPolicy.getMaxTotalParallelismPerTenant();
        if (maxJobs <= 0 && maxParallelism <= 0) {
            return;
        }
        Map<String, String> existing = jobs(tenant).readAllMap();
        if (maxJobs > 0 && existing.size() >= maxJobs) {
            throw new IllegalStateException(
                    "Tenant quota exceeded for tenant '" + tenant + "': maxJobsPerTenant=" + maxJobs
                            + " (submitting " + jobName + ")");
        }
        if (maxParallelism > 0) {
            long currentTotal = 0L;
            for (Map.Entry<String, String> e : existing.entrySet()) {
                currentTotal += specParallelism(e.getValue());
            }
            long projected = currentTotal + parallelismDelta;
            if (projected > maxParallelism) {
                throw new IllegalStateException(
                        "Tenant quota exceeded for tenant '" + tenant + "': maxTotalParallelismPerTenant="
                                + maxParallelism + " (current=" + currentTotal + ", requesting +"
                                + parallelismDelta + " for " + jobName + ")");
            }
        }
    }

    /** Tolerant parallelism read from a stored spec JSON (0 when the entry is unreadable). */
    private int specParallelism(String json) {
        try {
            return objectMapper.readTree(json).path("parallelism").asInt(0);
        } catch (Exception e) {
            log.warn("Could not read spec parallelism for quota check", e);
            return 0;
        }
    }

    private JobSpec toBuilder(JobSpec spec) {
        Map<String, String> config = spec.getConfig() == null ? Map.of() : new LinkedHashMap<>(spec.getConfig());
        return JobSpec.builder()
                .jobName(spec.getJobName())
                .tenant(spec.getTenant())
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
                .append(spec.getTenant()).append('|')
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

    private RMap<String, String> jobs(String tenant) {
        return redissonClient.<String, String>getMap(prefix + tenantSegment(tenant) + "jobs", StringCodec.INSTANCE);
    }

    private RMap<String, String> versions(String tenant) {
        return redissonClient.<String, String>getMap(prefix + tenantSegment(tenant) + "versions", StringCodec.INSTANCE);
    }

    /** @return "" for the default tenant, else "{tenant}:" — inserted after the prefix. */
    private static String tenantSegment(String tenant) {
        return StreamKeys.DEFAULT_TENANT.equals(tenant) ? "" : tenant + ":";
    }

    /**
     * Registered non-default tenants, read from the tenants index set (no Redis scan).
     * Sorted for deterministic resolution when several tenants claim the same job name.
     */
    private List<String> knownTenants() {
        try {
            Set<String> tenants = redissonClient.<String>getSet(tenantsKey, StringCodec.INSTANCE).readAll();
            List<String> out = new ArrayList<>(tenants);
            Collections.sort(out);
            return out;
        } catch (Exception e) {
            log.warn("Failed to read tenants index", e);
            return List.of();
        }
    }

    private void rememberTenant(String tenant) {
        if (StreamKeys.DEFAULT_TENANT.equals(tenant)) {
            return;
        }
        try {
            redissonClient.<String>getSet(tenantsKey, StringCodec.INSTANCE).add(tenant);
        } catch (Exception e) {
            log.warn("Failed to record tenant {} in the index", tenant, e);
        }
    }

    private RList<String> historyList(String tenant, String jobName) {
        return redissonClient.<String>getList(historyKey(tenant, jobName), StringCodec.INSTANCE);
    }

    private String historyKey(String tenant, String jobName) {
        return prefix + "history:" + tenantSegment(tenant) + jobName;
    }

    private String statusKey(String tenant, String jobName) {
        return prefix + "status:" + tenantSegment(tenant) + jobName;
    }

    /**
     * Atomic compare-and-set on the version hash: writes the new spec JSON, bumps the
     * version and appends the previous spec JSON to the job history in one step.
     * Returns -1 when the job vanished, -2 on version conflict, and the new version
     * on success.
     */
    private long casVersionAndWrite(String tenant, String jobName, long expectedVersion, String newJson, String oldJson) {
        List<Object> keys = List.of(prefix + tenantSegment(tenant) + "versions",
                prefix + tenantSegment(tenant) + "jobs", historyKey(tenant, jobName));
        Object result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, CAS_VERSION_AND_WRITE_LUA, RScript.ReturnType.LONG, keys,
                jobName, String.valueOf(expectedVersion), newJson, String.valueOf(expectedVersion + 1),
                oldJson, String.valueOf(historyMaxEntries));
        if (result == null) {
            throw new IllegalStateException("CAS script did not return a value for job " + jobName);
        }
        return ((Number) result).longValue();
    }

    private void writeStatus(String tenant, String jobName, JobState state, String instanceId, String detail, long updatedAt) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("state", state.name());
        fields.put("instanceId", nullToEmpty(instanceId));
        fields.put("detail", nullToEmpty(detail));
        fields.put("updatedAt", String.valueOf(updatedAt));
        redissonClient.<String, String>getMap(statusKey(tenant, jobName), StringCodec.INSTANCE).putAll(fields);
    }

    private void appendAudit(String tenant, JobControlOp op, String actor, String jobName, Long fromVersion,
                             Long toVersion, boolean allowed, String detail) {
        try {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("ts", String.valueOf(System.currentTimeMillis()));
            entry.put("tenant", nullToEmpty(tenant));
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
        entry.setTenant((String) fields.getOrDefault("tenant", StreamKeys.DEFAULT_TENANT));
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

    private JobSpec readOrNull(String json) {
        return json == null ? null : readJson(json);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
