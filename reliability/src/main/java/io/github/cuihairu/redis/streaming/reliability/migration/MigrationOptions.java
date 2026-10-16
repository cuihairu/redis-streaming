package io.github.cuihairu.redis.streaming.reliability.migration;

import lombok.Builder;
import lombok.Data;

/**
 * Options of one {@link TenantKeyMigrator#migrate(List, MigrationOptions)} call.
 */
@Data
@Builder
public class MigrationOptions {

    /**
     * Dry run (the default): scan and count only, no key is renamed. A dry run also
     * reports target collisions, so it shows whether the real run would be clean.
     */
    @Builder.Default
    private final boolean dryRun = true;

    /** SCAN COUNT hint for the family scan (>= 1; a larger value scans in fewer round trips). */
    @Builder.Default
    private final int scanCount = 100;

    /** {@code dryRun = true}, {@code scanCount = 100}. */
    public static MigrationOptions defaults() {
        return builder().build();
    }
}
