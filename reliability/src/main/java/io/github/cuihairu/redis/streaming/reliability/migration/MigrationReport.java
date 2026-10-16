package io.github.cuihairu.redis.streaming.reliability.migration;

import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Result of one {@link TenantKeyMigrator#migrate(List, MigrationOptions)} call: a total
 * plus one {@link FamilyResult} per family that was scanned.
 *
 * <p>{@code success} is true only when the run left the keyspace consistent with the
 * intent: no scan or validation error, no per-key failure and no target collision. In a
 * dry run the counts are what a run <em>would</em> do ({@code wouldMove}); in a real run
 * they are what it did ({@code moved}). A skipped target or a per-key failure means the
 * family is only partially moved — re-run it, a family is resumable.</p>
 */
@Getter
@ToString
public final class MigrationReport {

    private final boolean dryRun;
    private final boolean success;
    private final List<String> errors;
    private final List<FamilyResult> families;
    private final long totalScanned;
    private final long totalMoved;
    private final long totalWouldMove;
    private final long totalSkipped;
    private final long totalFailed;
    private final long durationMs;

    MigrationReport(boolean dryRun, boolean success, List<String> errors, List<FamilyResult> families,
                    long totalScanned, long totalMoved, long totalWouldMove, long totalSkipped,
                    long totalFailed, long durationMs) {
        this.dryRun = dryRun;
        this.success = success;
        this.errors = List.copyOf(errors);
        this.families = List.copyOf(families);
        this.totalScanned = totalScanned;
        this.totalMoved = totalMoved;
        this.totalWouldMove = totalWouldMove;
        this.totalSkipped = totalSkipped;
        this.totalFailed = totalFailed;
        this.durationMs = durationMs;
    }

    /** One scanned family: the per-key counters of it. */
    @Getter
    @ToString
    public static final class FamilyResult {

        private final String fromPrefix;
        private final String toPrefix;
        private long scanned;
        private long moved;
        private long wouldMove;
        private long skipped;
        private long failed;
        private String error;

        FamilyResult(String fromPrefix, String toPrefix) {
            this.fromPrefix = fromPrefix;
            this.toPrefix = toPrefix;
        }

        void addScanned() { scanned++; }

        void addMoved() { moved++; }

        void addWouldMove() { wouldMove++; }

        void addSkipped() { skipped++; }

        void addFailed() { failed++; }

        /** First failure of the family; further failures are only counted. */
        void noteError(String message) {
            if (error == null) {
                error = message;
            }
        }
    }
}
