package io.github.cuihairu.redis.streaming.reliability.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the tenant key migration engine (docs/Multi-Tenancy-Design.md):
 * prefix-scoped atomic renames, dry-run default, refusal to overwrite existing
 * targets, resumable idempotency. Real-Redis behavior (type/TTL preservation,
 * pattern reach) is covered by {@link TenantKeyMigratorIntegrationTest}.
 */
class TenantKeyMigratorTest {

    private static final String FROM = "stream:topic:";
    private static final String TO = "stream:topic:acme:";

    private RKeys keys;
    private TenantKeyMigrator migrator;

    @BeforeEach
    void setUp() {
        keys = mock(RKeys.class);
        migrator = new TenantKeyMigrator(keys);
    }

    private TenantKeyMigrator.KeyMove move() {
        return TenantKeyMigrator.KeyMove.of(FROM, TO);
    }

    private MigrationOptions apply() {
        return MigrationOptions.builder().dryRun(false).build();
    }

    // ===== KeyMove validation =====

    @Test
    void keyMoveRejectsBlankPrefixes() {
        assertThrows(IllegalArgumentException.class, () -> TenantKeyMigrator.KeyMove.of("  ", TO));
        assertThrows(IllegalArgumentException.class, () -> TenantKeyMigrator.KeyMove.of(FROM, " "));
        assertThrows(IllegalArgumentException.class, () -> TenantKeyMigrator.KeyMove.of(null, TO));
    }

    @Test
    void keyMoveRejectsGlobCharacters() {
        assertThrows(IllegalArgumentException.class, () -> TenantKeyMigrator.KeyMove.of(FROM + "*", TO));
        assertThrows(IllegalArgumentException.class, () -> TenantKeyMigrator.KeyMove.of(FROM, TO + "?"));
        assertThrows(IllegalArgumentException.class, () -> TenantKeyMigrator.KeyMove.of(FROM, TO + "["));
        assertThrows(IllegalArgumentException.class, () -> TenantKeyMigrator.KeyMove.of(FROM, TO + "a\\b"));
    }

    // ===== move-set validation =====

    @Test
    void identityMoveIsValidatedWithoutScanning() {
        MigrationReport report = migrator.migrate(List.of(TenantKeyMigrator.KeyMove.of(FROM, FROM)), apply());

        assertFalse(report.isSuccess());
        assertEquals(1, report.getErrors().size());
        verify(keys, never()).getKeys(any(KeysScanOptions.class));
        verify(keys, never()).renamenx(anyString(), anyString());
    }

    @Test
    void duplicateFromPrefixesAreValidatedWithoutScanning() {
        MigrationReport report = migrator.migrate(List.of(move(), move()), apply());

        assertFalse(report.isSuccess());
        assertEquals(1, report.getErrors().size());
        // a malformed move set is failed closed: no family is scanned or moved
        assertEquals(0, report.getFamilies().size());
        assertEquals(0, report.getTotalScanned());
        verify(keys, never()).getKeys(any(KeysScanOptions.class));
        verify(keys, never()).renamenx(anyString(), anyString());
    }

    // ===== dry run (the default) =====

    @Test
    void dryRunIsTheDefaultAndReportsMovesWithoutRenaming() {
        when(keys.getKeys(any(KeysScanOptions.class))).thenReturn(List.of(FROM + "orders:0", FROM + "orders:1"));

        MigrationReport report = migrator.migrate(List.of(move()));

        assertTrue(report.isDryRun());
        assertEquals(1, report.getFamilies().size());
        var family = report.getFamilies().get(0);
        assertEquals(2, family.getScanned());
        assertEquals(2, family.getWouldMove());
        assertEquals(0, family.getMoved());
        assertEquals(0, family.getSkipped());
        assertEquals(0, family.getFailed());
        assertTrue(report.isSuccess());
        verify(keys, never()).renamenx(anyString(), anyString());
        // a dry run still checks both targets before promising a move
        verify(keys).countExists(TO + "orders:0");
        verify(keys).countExists(TO + "orders:1");
    }

    @Test
    void dryRunReportsExistingTargets() {
        when(keys.getKeys(any(KeysScanOptions.class)))
                .thenReturn(List.of(FROM + "orders:0", FROM + "orders:1"));
        when(keys.countExists(TO + "orders:0")).thenReturn(1L);

        MigrationReport report = migrator.migrate(List.of(move()));

        // orders:0 has an existing target (skipped), orders:1 has none (would move)
        assertEquals(1, report.getFamilies().get(0).getWouldMove());
        assertEquals(1, report.getFamilies().get(0).getSkipped());
        assertFalse(report.isSuccess());
        verify(keys, never()).renamenx(anyString(), anyString());
    }

    // ===== apply =====

    @Test
    void applyRenamesEachKeyUnderTheTargetPrefix() {
        when(keys.getKeys(any(KeysScanOptions.class))).thenReturn(List.of(FROM + "orders:0", FROM + "orders:1"));
        when(keys.renamenx(anyString(), anyString())).thenReturn(true);

        MigrationReport report = migrator.migrate(List.of(move()), apply());

        verify(keys).renamenx(FROM + "orders:0", TO + "orders:0");
        verify(keys).renamenx(FROM + "orders:1", TO + "orders:1");
        assertEquals(2, report.getFamilies().get(0).getMoved());
        assertEquals(2, report.getTotalMoved());
        assertTrue(report.isSuccess());
        assertFalse(report.isDryRun());
    }

    @Test
    void applyRefusesToOverwriteAnExistingTarget() {
        when(keys.getKeys(any(KeysScanOptions.class))).thenReturn(List.of(FROM + "orders:0", FROM + "orders:1"));
        when(keys.renamenx(FROM + "orders:0", TO + "orders:0")).thenReturn(true);
        when(keys.renamenx(FROM + "orders:1", TO + "orders:1")).thenReturn(false);

        MigrationReport report = migrator.migrate(List.of(move()), apply());

        assertEquals(1, report.getFamilies().get(0).getMoved());
        assertEquals(1, report.getFamilies().get(0).getSkipped());
        assertFalse(report.isSuccess());
        // the refused key must still be under its source prefix (never force-renamed)
        verify(keys, never()).rename(FROM + "orders:1", TO + "orders:1");
    }

    @Test
    void applyContinuesAfterAPerKeyFailure() {
        when(keys.getKeys(any(KeysScanOptions.class))).thenReturn(List.of(FROM + "a", FROM + "b"));
        when(keys.renamenx(FROM + "a", TO + "a")).thenThrow(new IllegalStateException("connection reset"));
        when(keys.renamenx(FROM + "b", TO + "b")).thenReturn(true);

        MigrationReport report = migrator.migrate(List.of(move()), apply());

        assertEquals(1, report.getFamilies().get(0).getMoved());
        assertEquals(1, report.getFamilies().get(0).getFailed());
        assertNotNull(report.getFamilies().get(0).getError());
        assertFalse(report.isSuccess());
        assertEquals(1, report.getTotalFailed());
    }

    @Test
    void blankTargetPrefixIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> migrator.migrate(
                List.of(TenantKeyMigrator.KeyMove.of(TO, "")), apply()));
    }

    // ===== resumability =====

    @Test
    void aNestedTargetNamespaceIsNotRemigrated() {
        // adoption moves "stream:topic:" -> "stream:topic:acme:", so the target sits
        // inside the source pattern: already-migrated keys must never move again
        when(keys.getKeys(any(KeysScanOptions.class)))
                .thenReturn(List.of(FROM + "orders:0", TO + "orders:0"));
        when(keys.renamenx(anyString(), anyString())).thenReturn(true);

        MigrationReport report = migrator.migrate(List.of(move()), apply());

        verify(keys).renamenx(FROM + "orders:0", TO + "orders:0");
        verify(keys, never()).renamenx(eq(TO + "orders:0"), anyString());
        assertEquals(1, report.getTotalScanned());
        assertEquals(1, report.getTotalMoved());
        assertTrue(report.isSuccess());
    }


    @Test
    void secondRunAfterAMigratedFamilyIsANoOp() {
        when(keys.getKeys(any(KeysScanOptions.class))).thenReturn(List.of());

        MigrationReport report = migrator.migrate(List.of(move()), apply());

        assertEquals(0, report.getFamilies().get(0).getScanned());
        assertTrue(report.isSuccess());
        verify(keys, never()).renamenx(anyString(), anyString());
    }

    // ===== overlapping families =====

    @Test
    void theLongestMatchingPrefixOwnsAnOverlappingKey() {
        TenantKeyMigrator.KeyMove broad = TenantKeyMigrator.KeyMove.of(FROM, TO);
        TenantKeyMigrator.KeyMove narrow = TenantKeyMigrator.KeyMove.of(FROM + "ck:", FROM + "ck:acme:");
        // the broad scan sees both keys (plus a foreign key), the narrow one only its own
        when(keys.getKeys(any(KeysScanOptions.class)))
                .thenReturn(List.of(FROM + "ck:1", FROM + "s:1", "unrelated:x"))
                .thenReturn(List.of(FROM + "ck:1"));
        when(keys.renamenx(anyString(), anyString())).thenReturn(true);

        MigrationReport report = migrator.migrate(List.of(broad, narrow), apply());

        // the broad family must not swallow the narrow family's key (nor move it twice)
        verify(keys, never()).renamenx(FROM + "ck:1", TO + "ck:1");
        verify(keys).renamenx(FROM + "ck:1", FROM + "ck:acme:1");
        verify(keys).renamenx(FROM + "s:1", TO + "s:1");
        verify(keys, never()).renamenx(eq("unrelated:x"), anyString());
        assertEquals(1, report.getFamilies().get(0).getScanned());
        assertEquals(1, report.getFamilies().get(0).getMoved());
        assertEquals(1, report.getFamilies().get(1).getScanned());
        assertEquals(1, report.getFamilies().get(1).getMoved());
        assertEquals(2, report.getTotalScanned());
        assertTrue(report.isSuccess());
    }

    @Test
    void aScanFailureFailsTheRunButKeepsTheFamilyInTheReport() {
        when(keys.getKeys(any(KeysScanOptions.class))).thenThrow(new IllegalStateException("down"));

        MigrationReport report = migrator.migrate(List.of(move()), apply());

        assertEquals(1, report.getErrors().size());
        assertEquals(1, report.getFamilies().size());
        assertEquals(0, report.getFamilies().get(0).getScanned());
        assertFalse(report.isSuccess());
        verify(keys, never()).renamenx(anyString(), anyString());
    }

    // ===== public entry point =====

    @Test
    void migratesFromTheRedissonClientEntry() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getKeys()).thenReturn(keys);
        when(keys.getKeys(any(KeysScanOptions.class))).thenReturn(List.of(FROM + "orders:0"));
        when(keys.renamenx(anyString(), anyString())).thenReturn(true);

        MigrationReport report = new TenantKeyMigrator(redissonClient)
                .migrate(List.of(move()), apply());

        assertEquals(1, report.getTotalMoved());
        verify(keys).renamenx(FROM + "orders:0", TO + "orders:0");
    }

    // ===== report totals =====

    @Test
    void reportTotalsAggregateFamilies() {
        when(keys.getKeys(any(KeysScanOptions.class)))
                .thenReturn(List.of(FROM + "a:0", FROM + "a:1"))
                .thenReturn(List.of("other:pre:b:0"));
        when(keys.countExists(anyString())).thenReturn(1L);
        TenantKeyMigrator.KeyMove secondFamily = TenantKeyMigrator.KeyMove.of("other:pre:", "other:pre:acme:");

        MigrationReport report = migrator.migrate(List.of(move(), secondFamily));

        assertEquals(2, report.getFamilies().size());
        assertEquals(3, report.getTotalScanned());
        assertEquals(3, report.getTotalSkipped());
        assertEquals(0, report.getTotalMoved());
        assertFalse(report.isSuccess());
    }
}
