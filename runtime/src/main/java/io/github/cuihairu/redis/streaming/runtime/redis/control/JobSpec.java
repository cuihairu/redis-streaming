package io.github.cuihairu.redis.streaming.runtime.redis.control;

import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Declarative, serializable description of a streaming job.
 *
 * <p>A spec references a pipeline by <em>factory name</em> instead of embedding the
 * pipeline graph (DataStream graphs are lambdas and cannot be persisted). Execution
 * side agents resolve the factory from a local registry and apply {@link #config} to
 * build the pipeline. {@link #version} is owned by the control plane: it starts at 1
 * on submit and increases monotonically on every upgrade/rollback; it is also the
 * compare-and-set token used to reject concurrent rewrites.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class JobSpec {

    /** Unique job name. Doubles as the keyed-state/checkpoint job identity. */
    private String jobName;

    /**
     * Tenant namespace of the job (docs/Multi-Tenancy-Design.md). {@code "default"} keeps the
     * pre-tenant layout (no key segment) so existing deployments need no migration. Immutable
     * after submit, like {@link #jobName}: changing the tenant would relocate state, streams and
     * checkpoints, which a rolling upgrade cannot express.
     */
    @Builder.Default
    private String tenant = StreamKeys.DEFAULT_TENANT;

    /** Name of the pipeline factory registered on the execution side. */
    private String pipelineFactory;

    /** Free-form string configuration passed to the pipeline factory. */
    private Map<String, String> config;

    /** Pipeline parallelism (subtask count). */
    private int parallelism;

    /** Monotonic spec version, assigned and CAS-checked by the control plane. */
    private long version;

    /** Human readable description. */
    private String description;

    /** Identity recorded on the last change. */
    private String updatedBy;

    /** Epoch millis of the last change. */
    private long updatedAt;

    /** Content hash of the semantic fields; agents compare it to detect drift. */
    private String specHash;

    /**
     * Log-safe rendering: Lombok's generated {@code toString} would embed {@link #config}
     * verbatim, so a {@code log.debug("... {}", spec)} could leak a secret literal. This
     * override routes the config through {@link LogRedaction#renderSpec}; Lombok skips
     * generating a {@code toString} when one is already declared.
     */
    @Override
    public String toString() {
        return LogRedaction.renderSpec(this);
    }
}
