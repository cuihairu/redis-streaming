package io.github.cuihairu.redis.streaming.reliability.migration;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.redisson.api.RKeys;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Moves the keys of one key family from one prefix to another so an existing
 * deployment can adopt (or leave) a tenant namespace in place
 * (docs/Multi-Tenancy-Design.md). The tenant segment is inserted right after the
 * configured prefix, so a family migration is a plain prefix move:
 * {@code stream:topic:orders:p:0 -> stream:topic:acme:orders:p:0}.
 *
 * <p>One move is one atomic {@code RENAMENX} per key, so payloads, key types and TTLs
 * are preserved and no key is ever half-moved. Existing target keys are never
 * overwritten (a target collision is reported as skipped, the source key stays put).
 * A family is therefore resumable: an interrupted pass can be re-run and only the
 * keys still under the source prefix are moved, so a second run is a clean no-op.</p>
 *
 * <p>Families may overlap (the runtime state prefix {@code streaming:runtime:} also
 * matches the checkpoint family {@code streaming:runtime:checkpoint:}), so a key is
 * always owned by the <em>most specific</em> (longest) source prefix that matches it
 * and is moved exactly once. The keys of a family are collected before any rename,
 * because a rename mutates the keyspace that SCAN is still walking. When the target
 * namespace is nested inside the source one (tenant adoption), a key already under
 * the target prefix is left alone, so a re-run never tenantizes a key twice.</p>
 *
 * <p>Operational precondition: stop the writers of a family before migrating it, and
 * stop the jobs before a tenant namespace is switched, otherwise state written
 * during the move lands in the old namespace and is invisible after the switch.</p>
 *
 * <p>The engine only moves keys; it does not know which prefixes belong to which
 * module, so the caller supplies the move list (defaults per module are listed in
 * docs/Multi-Tenancy-Design.md). Dry run is the default.</p>
 */
public final class TenantKeyMigrator {

    private static final Set<Character> GLOB_CHARS = Set.of('*', '?', '[', '\\');

    private final RKeys keys;

    public TenantKeyMigrator(RedissonClient redissonClient) {
        this(redissonClient.getKeys());
    }

    TenantKeyMigrator(RKeys keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /** Dry run: reports what a move would do without touching the keyspace. */
    public MigrationReport migrate(List<KeyMove> moves) {
        return migrate(moves, MigrationOptions.defaults());
    }

    public MigrationReport migrate(List<KeyMove> moves, MigrationOptions options) {
        Objects.requireNonNull(moves, "moves");
        Objects.requireNonNull(options, "options");
        long startNs = System.nanoTime();
        boolean dryRun = options.isDryRun();
        List<String> errors = new ArrayList<>();
        long scanned = 0, moved = 0, wouldMove = 0, skipped = 0, failed = 0;

        // A move set that repeats a family, or a family that would move onto itself, can
        // never be executed: validate it before any key is scanned.
        Map<String, KeyMove> accepted = new LinkedHashMap<>();
        for (KeyMove move : moves) {
            Objects.requireNonNull(move, "move");
            String from = move.getFromPrefix();
            if (accepted.containsKey(from)) {
                errors.add("duplicate source prefix '" + from + "'");
            } else if (from.equals(move.getToPrefix())) {
                errors.add("identity move (source equals target) for prefix '" + from + "'");
            } else {
                accepted.put(from, move);
            }
        }

        // A malformed move set is failed closed: not even the well-formed families are
        // scanned or moved, so a typo in one family can never move another one.
        if (!errors.isEmpty()) {
            return new MigrationReport(dryRun, false, errors, List.of(),
                    0, 0, 0, 0, 0, (System.nanoTime() - startNs) / 1_000_000L);
        }

        // Scan every family, then hand each key to the most specific source prefix that
        // matches it, so overlapping families never move (or double-move) a foreign key.
        Map<String, KeyMove> ownerOfKey = new HashMap<>();
        Map<KeyMove, List<String>> keysOfMove = new LinkedHashMap<>();
        for (KeyMove move : accepted.values()) {
            try {
                Iterable<String> scan = keys.getKeys(KeysScanOptions.defaults()
                        .pattern(move.getFromPrefix() + "*").chunkSize(options.getScanCount()));
                for (String key : scan) {
                    if (!key.startsWith(move.getFromPrefix())) {
                        continue;
                    }
                    // Tenant adoption nests the target inside the source pattern
                    // ("stream:topic:" -> "stream:topic:acme:"), so a key already under
                    // the target prefix belongs to the destination, not to the unmigrated
                    // remainder: skip it, or a re-run would tenant the same keys twice.
                    if (move.getToPrefix().startsWith(move.getFromPrefix())
                            && key.startsWith(move.getToPrefix())) {
                        continue;
                    }
                    KeyMove owner = ownerOfKey.get(key);
                    if (owner == null || move.getFromPrefix().length() > owner.getFromPrefix().length()) {
                        ownerOfKey.put(key, move);
                    }
                }
            } catch (Exception e) {
                errors.add("scan failed for prefix '" + move.getFromPrefix() + "': " + e.getMessage());
            }
        }
        for (Map.Entry<String, KeyMove> entry : ownerOfKey.entrySet()) {
            keysOfMove.computeIfAbsent(entry.getValue(), m -> new ArrayList<>()).add(entry.getKey());
        }

        List<MigrationReport.FamilyResult> families = new ArrayList<>();
        for (KeyMove move : accepted.values()) {
            MigrationReport.FamilyResult family = new MigrationReport.FamilyResult(
                    move.getFromPrefix(), move.getToPrefix());
            for (String key : keysOfMove.getOrDefault(move, List.of())) {
                family.addScanned();
                String target = move.getToPrefix() + key.substring(move.getFromPrefix().length());
                try {
                    if (dryRun) {
                        if (keys.countExists(target) > 0) {
                            family.addSkipped();
                        } else {
                            family.addWouldMove();
                        }
                    } else if (keys.renamenx(key, target)) {
                        family.addMoved();
                    } else {
                        family.addSkipped();
                        family.noteError("target already exists: " + target);
                    }
                } catch (Exception e) {
                    family.addFailed();
                    family.noteError(key + ": " + e.getMessage());
                }
            }
            scanned += family.getScanned();
            moved += family.getMoved();
            wouldMove += family.getWouldMove();
            skipped += family.getSkipped();
            failed += family.getFailed();
            families.add(family);
        }

        boolean success = errors.isEmpty() && failed == 0 && skipped == 0;
        return new MigrationReport(dryRun, success, errors, families,
                scanned, moved, wouldMove, skipped, failed, (System.nanoTime() - startNs) / 1_000_000L);
    }

    /**
     * One family to move: every key whose name starts with {@code fromPrefix} is
     * renamed to the same relative path under {@code toPrefix}. Both prefixes are
     * plain prefixes, never glob patterns, so the target path of a key is always
     * deterministic.
     */
    @Getter
    @EqualsAndHashCode
    public static final class KeyMove {

        private final String fromPrefix;
        private final String toPrefix;

        private KeyMove(String fromPrefix, String toPrefix) {
            this.fromPrefix = fromPrefix;
            this.toPrefix = toPrefix;
        }

        public static KeyMove of(String fromPrefix, String toPrefix) {
            validatePrefix("from", fromPrefix);
            validatePrefix("to", toPrefix);
            return new KeyMove(fromPrefix.trim(), toPrefix.trim());
        }

        private static void validatePrefix(String which, String prefix) {
            if (prefix == null || prefix.isBlank()) {
                throw new IllegalArgumentException("key move " + which + " prefix must not be blank");
            }
            for (char c : prefix.toCharArray()) {
                if (GLOB_CHARS.contains(c)) {
                    throw new IllegalArgumentException("key move " + which
                            + " prefix must not contain glob characters " + GLOB_CHARS
                            + ", got '" + prefix + "'");
                }
            }
        }
    }
}
