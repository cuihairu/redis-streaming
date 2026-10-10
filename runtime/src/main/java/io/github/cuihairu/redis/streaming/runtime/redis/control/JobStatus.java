package io.github.cuihairu.redis.streaming.runtime.redis.control;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Observed/desired runtime status of a job, reported by execution side agents.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobStatus {

    /** Lifecycle state (desired or observed). */
    private JobState state;

    /** Instance that owns (or last touched) the job locally; blank when no owner. */
    private String instanceId;

    /** Free-form detail (e.g. failure message). */
    private String detail;

    /** Epoch millis of the last status write. */
    private long updatedAt;
}
