package io.github.cuihairu.redis.streaming.reliability.migration;

import io.github.cuihairu.redis.streaming.core.utils.Tenant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The key families a tenant namespace spans, and the move pairs that adopt or leave
 * a tenant in place (docs/Multi-Tenancy-Design.md, "tenant key migration engine").
 *
 * <p>The defaults are the module defaults the framework ships with; a deployment that
 * customized a prefix supplies its own with {@link #with(Family, String)}, because the
 * engine cannot know which prefix a deployment actually uses. The tenant name goes
 * through the shared {@link Tenant} contract, so a name that is legal here is legal in
 * every module whose keys are being moved.</p>
 *
 * <p>A family table is a set that has to be moved <em>together</em>, not one family at a
 * time: the default source prefixes nest over each other in two places.
 * {@code streaming:runtime} is a prefix of the checkpoint, sink-dedup and control-plane
 * prefixes, and {@code redis_streaming} is a prefix of {@code redis_streaming_registry}.
 * Moving only the wide family drags the narrow one along to the wrong place (a checkpoint
 * would land at {@code streaming:runtime:acme:checkpoint:job:1}). {@link TenantKeyMigrator}
 * assigns an overlapping key to the longest matching source prefix, so a run that selects
 * both families is correct; a run that selects only the wide one is not, which is what
 * {@link #swallowed(Collection)} is for.</p>
 */
public final class TenantKeyFamilies {

    private final Map<Family, String> prefixes;

    private TenantKeyFamilies(Map<Family, String> prefixes) {
        this.prefixes = prefixes;
    }

    /** All families, every prefix at the framework default. */
    public static TenantKeyFamilies defaults() {
        return new TenantKeyFamilies(Map.copyOf(Map.of()));
    }

    /** This table with one family's source prefix replaced (the input table is unchanged). */
    public TenantKeyFamilies with(Family family, String prefix) {
        String trimmed = requirePrefix(prefix);
        Map<Family, String> overridden = new EnumMap<>(Family.class);
        overridden.putAll(prefixes);
        overridden.put(family, trimmed);
        return new TenantKeyFamilies(Map.copyOf(overridden));
    }

    /** The source prefix in force for a family: the override if one was set, else the default. */
    public String prefixOf(Family family) {
        String overridden = prefixes.get(family);
        return overridden == null ? family.defaultPrefix() : overridden;
    }

    /**
     * The source prefix every family has in force for one direction, so a caller can check
     * the nesting of a selected subset against the ones it did not select.
     */
    public Map<Family, String> sourcePrefixes(String tenant, Direction direction) {
        Map<Family, String> resolved = new EnumMap<>(Family.class);
        for (Family family : Family.values()) {
            resolved.put(family, sourcePrefix(family, tenant, direction));
        }
        return resolved;
    }

    public String sourcePrefix(Family family, String tenant, Direction direction) {
        if (direction == Direction.ADOPT) {
            return prefixOf(family);
        }
        return scopedPrefix(prefixOf(family), requireTenant(tenant));
    }

    /**
     * The tenant-scoped form of a family prefix. Two families here terminate their prefix
     * with {@code ":"} (the runtime config appends the tenant and its own colon), so a
     * plain {@link Tenant#scopedPrefix} would double the colon: a checkpoint key must land
     * at {@code streaming:runtime:checkpoint:acme:job:1}, not {@code ...checkpoint::acme}.
     */
    public static String scopedPrefix(String prefix, String tenant) {
        String name = requireTenant(tenant);
        return prefix.endsWith(":") ? prefix + name + ":" : Tenant.scopedPrefix(prefix, name);
    }

    /**
     * Moves that adopt a tenant: every key under a family's prefix is re-homed under the
     * tenant-scoped prefix ({@code stream:topic:orders:p:0 -> stream:topic:acme:orders:p:0}).
     */
    public List<TenantKeyMigrator.KeyMove> adoption(Collection<Family> selected, String tenant) {
        String name = requireTenant(tenant);
        List<TenantKeyMigrator.KeyMove> moves = new ArrayList<>();
        for (Family family : selected) {
            moves.add(TenantKeyMigrator.KeyMove.of(
                    prefixOf(family), scopedPrefix(prefixOf(family), name)));
        }
        return List.copyOf(moves);
    }

    /** Moves that leave a tenant: the tenant segment comes back out (the inverse direction). */
    public List<TenantKeyMigrator.KeyMove> exit(Collection<Family> selected, String tenant) {
        String name = requireTenant(tenant);
        List<TenantKeyMigrator.KeyMove> moves = new ArrayList<>();
        for (Family family : selected) {
            moves.add(TenantKeyMigrator.KeyMove.of(
                    scopedPrefix(prefixOf(family), name), prefixOf(family)));
        }
        return List.copyOf(moves);
    }

    /**
     * Families left out of a run whose keys the selected families' scans would swallow:
     * selecting only the wide family of a nesting pair would move the narrow family's keys
     * to the wrong place. Empty when the selected set is nested-safe.
     */
    public List<Family> swallowed(Collection<Family> selected) {
        List<Family> swallowed = new ArrayList<>();
        for (Family candidate : Family.values()) {
            if (selected.contains(candidate)) {
                continue;
            }
            String nested = prefixOf(candidate);
            for (Family owner : selected) {
                String owned = prefixOf(owner);
                // strict containment only: an equal prefix is a duplicate, which the
                // migrator rejects with its own message
                if (nested.length() > owned.length() && nested.startsWith(owned)) {
                    swallowed.add(candidate);
                    break;
                }
            }
        }
        return List.copyOf(swallowed);
    }

    /** Normalizes a tenant name and rejects the one that owns no key segment. */
    public static String requireTenant(String tenant) {
        String name = Tenant.normalize(tenant);
        if (Tenant.DEFAULT_TENANT.equals(name)) {
            throw new IllegalArgumentException(
                    "the default tenant owns no key segment, there is nothing to migrate");
        }
        return name;
    }

    private static String requirePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("family prefix must not be blank");
        }
        return prefix.trim();
    }

    /** One key family: its CLI name and the prefix the framework defaults it to. */
    public enum Family {

        MQ_CONTROL("mq-control", "streaming:mq"),
        MQ_DATA("mq-data", "stream:topic"),
        RUNTIME_STATE("runtime-state", "streaming:runtime"),
        CHECKPOINT("checkpoint", "streaming:runtime:checkpoint:"),
        SINK_DEDUP("sink-dedup", "streaming:runtime:sinkDedup:"),
        CONTROL_PLANE("control-plane", "streaming:runtime:control:"),
        REGISTRY("registry", "redis_streaming_registry"),
        CONFIG("config", "redis_streaming");

        private final String name;
        private final String defaultPrefix;

        Family(String name, String defaultPrefix) {
            this.name = name;
            this.defaultPrefix = defaultPrefix;
        }

        /** The CLI flag value, distinct from {@link Enum#name()} which is the constant name. */
        public String cliName() {
            return name;
        }

        public String defaultPrefix() {
            return defaultPrefix;
        }

        /** @throws IllegalArgumentException on a name that is not in the table */
        public static Family of(String name) {
            for (Family family : values()) {
                if (family.name.equals(name)) {
                    return family;
                }
            }
            throw new IllegalArgumentException(
                    "unknown family '" + name + "', expected one of " + labels());
        }

        /** Every family name, comma separated (the CLI lists these for a bad --family). */
        public static String labels() {
            return String.join(", ", labelsList());
        }

        private static List<String> labelsList() {
            List<String> all = new ArrayList<>();
            for (Family family : values()) {
                all.add(family.cliName());
            }
            return all;
        }
    }

    /** Which way the tenant segment moves. */
    public enum Direction {

        /** Adopt: the tenant segment goes in (the default). */
        ADOPT("in"),
        /** Exit: the tenant segment comes out (rollback). */
        EXIT("out");

        private final String label;

        Direction(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** @throws IllegalArgumentException on anything other than {@code in} or {@code out} */
        public static Direction of(String label) {
            for (Direction direction : values()) {
                if (direction.label.equals(label)) {
                    return direction;
                }
            }
            throw new IllegalArgumentException(
                    "unknown direction '" + label + "', expected 'in' or 'out'");
        }
    }
}
