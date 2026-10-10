package io.github.cuihairu.redis.streaming.runtime.redis.control;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One control-plane audit record: who attempted which operation on which job and
 * whether it was allowed. Denied attempts are audited too.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditEntry {

    /** Epoch millis of the attempt. */
    private long ts;

    /** Identity of the caller. */
    private String actor;

    /** Tenant namespace of the target job ("default" when the job predates tenants). */
    private String tenant;

    /** Attempted operation. */
    private JobControlOp op;

    /** Target job name. */
    private String jobName;

    /** Spec version before the change (null when not applicable, e.g. submit). */
    private Long fromVersion;

    /** Spec version after the change (null when the attempt was denied). */
    private Long toVersion;

    /** Whether the operation was allowed. */
    private boolean allowed;

    /** Free-form detail (rejection reason, failure note, ...). */
    private String detail;
}
