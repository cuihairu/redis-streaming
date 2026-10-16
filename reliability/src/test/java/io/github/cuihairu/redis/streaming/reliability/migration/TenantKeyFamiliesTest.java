package io.github.cuihairu.redis.streaming.reliability.migration;

import io.github.cuihairu.redis.streaming.reliability.migration.TenantKeyFamilies.Direction;
import io.github.cuihairu.redis.streaming.reliability.migration.TenantKeyFamilies.Family;
import io.github.cuihairu.redis.streaming.reliability.migration.TenantKeyMigrator.KeyMove;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the tenant key family table (docs/Multi-Tenancy-Design.md):
 * the default prefixes, the adoption and exit move pairs, and the nesting check that
 * stops a subset migration from dragging a neighbour family along.
 */
class TenantKeyFamiliesTest {

    // ===== the table =====

    @Test
    void everyFamilyListsThePrefixItsModuleDefaultsTo() {
        assertEquals("streaming:mq", Family.MQ_CONTROL.defaultPrefix());
        assertEquals("stream:topic", Family.MQ_DATA.defaultPrefix());
        assertEquals("streaming:runtime", Family.RUNTIME_STATE.defaultPrefix());
        assertEquals("streaming:runtime:checkpoint:", Family.CHECKPOINT.defaultPrefix());
        assertEquals("streaming:runtime:sinkDedup:", Family.SINK_DEDUP.defaultPrefix());
        assertEquals("streaming:runtime:control:", Family.CONTROL_PLANE.defaultPrefix());
        assertEquals("redis_streaming_registry", Family.REGISTRY.defaultPrefix());
        assertEquals("redis_streaming", Family.CONFIG.defaultPrefix());
    }

    @Test
    void familyOfResolvesCliNamesAndRejectsUnknownOnes() {
        assertSame(Family.MQ_DATA, Family.of("mq-data"));
        assertSame(Family.CHECKPOINT, Family.of("checkpoint"));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Family.of("topic"));
        assertTrue(thrown.getMessage().contains("unknown family 'topic'"));
        assertTrue(thrown.getMessage().contains("mq-data"));
    }

    @Test
    void directionOfResolvesInAndOutAndRejectsTheRest() {
        assertSame(Direction.ADOPT, Direction.of("in"));
        assertSame(Direction.EXIT, Direction.of("out"));
        assertThrows(IllegalArgumentException.class, () -> Direction.of("outwards"));
    }

    // ===== tenant names go through the shared contract =====

    @Test
    void requireTenantRejectsTheDefaultTenantBecauseItOwnsNoSegment() {
        assertThrows(IllegalArgumentException.class, () -> TenantKeyFamilies.requireTenant("default"));
        assertThrows(IllegalArgumentException.class, () -> TenantKeyFamilies.requireTenant(null));
        assertThrows(IllegalArgumentException.class, () -> TenantKeyFamilies.requireTenant("  "));
    }

    @Test
    void requireTenantKeepsTheSharedGrammar() {
        assertEquals("a.b-c_1", TenantKeyFamilies.requireTenant("a.b-c_1"));
        assertThrows(IllegalArgumentException.class, () -> TenantKeyFamilies.requireTenant("a:b"));
        assertThrows(IllegalArgumentException.class, () -> TenantKeyFamilies.requireTenant("ac me"));
    }

    // ===== move pairs =====

    @Test
    void adoptionInsertsTheTenantSegmentRightAfterEveryPrefix() {
        TenantKeyFamilies families = TenantKeyFamilies.defaults();
        List<KeyMove> moves = families.adoption(List.of(Family.values()), "acme");

        assertEquals(8, moves.size());
        List<String> targets = moves.stream().map(KeyMove::getToPrefix).toList();
        assertEquals(List.of(
                "streaming:mq:acme",
                "stream:topic:acme",
                "streaming:runtime:acme",
                "streaming:runtime:checkpoint:acme:",
                "streaming:runtime:sinkDedup:acme:",
                "streaming:runtime:control:acme:",
                "redis_streaming_registry:acme",
                "redis_streaming:acme"), targets);
    }

    @Test
    void exitMovesTheTenantSegmentOut() {
        TenantKeyFamilies families = TenantKeyFamilies.defaults();
        List<KeyMove> moves = families.exit(
                List.of(Family.MQ_DATA, Family.CONFIG), "acme");

        assertEquals(KeyMove.of("stream:topic:acme", "stream:topic"), moves.get(0));
        assertEquals(KeyMove.of("redis_streaming:acme", "redis_streaming"), moves.get(1));
    }

    @Test
    void adoptionAndExitAreInverseOfEachOther() {
        TenantKeyFamilies families = TenantKeyFamilies.defaults();
        List<Family> all = List.of(Family.values());
        List<KeyMove> adoption = families.adoption(all, "acme");
        List<KeyMove> exit = families.exit(all, "acme");

        for (int i = 0; i < all.size(); i++) {
            assertEquals(adoption.get(i).getFromPrefix(), exit.get(i).getToPrefix());
            assertEquals(adoption.get(i).getToPrefix(), exit.get(i).getFromPrefix());
        }
    }

    // ===== prefix overrides =====

    @Test
    void withOverridesOnlyOneFamilyAndKeepsTheInputTable() {
        TenantKeyFamilies defaults = TenantKeyFamilies.defaults();
        TenantKeyFamilies customized = defaults.with(Family.MQ_DATA, "myapp:stream");

        assertEquals("myapp:stream", customized.prefixOf(Family.MQ_DATA));
        assertEquals("streaming:mq", customized.prefixOf(Family.MQ_CONTROL));
        assertEquals("stream:topic", defaults.prefixOf(Family.MQ_DATA));
        assertEquals("myapp:stream:acme",
                customized.adoption(List.of(Family.MQ_DATA), "acme").get(0).getToPrefix());
    }

    @Test
    void withRejectsABlankPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> TenantKeyFamilies.defaults().with(Family.CONFIG, " "));
    }

    @Test
    void sourcePrefixesResolveForAdoptionAndExit() {
        TenantKeyFamilies families = TenantKeyFamilies.defaults().with(
                Family.MQ_DATA, "myapp:stream");

        Map<Family, String> adoption = families.sourcePrefixes("acme", Direction.ADOPT);
        assertEquals("myapp:stream", adoption.get(Family.MQ_DATA));
        assertEquals("streaming:runtime", adoption.get(Family.RUNTIME_STATE));

        Map<Family, String> exit = families.sourcePrefixes("acme", Direction.EXIT);
        assertEquals("myapp:stream:acme", exit.get(Family.MQ_DATA));
        assertEquals("streaming:runtime:acme", exit.get(Family.RUNTIME_STATE));
    }

    // ===== nesting =====

    @Test
    void swallowedFlagsTheRuntimeNesting() {
        TenantKeyFamilies families = TenantKeyFamilies.defaults();

        List<Family> swallowed = families.swallowed(List.of(Family.RUNTIME_STATE));

        assertEquals(Arrays.asList(Family.CHECKPOINT, Family.SINK_DEDUP, Family.CONTROL_PLANE),
                swallowed);
    }

    @Test
    void swallowedFlagsTheConfigRegistryNesting() {
        TenantKeyFamilies families = TenantKeyFamilies.defaults();

        assertEquals(List.of(Family.REGISTRY), families.swallowed(List.of(Family.CONFIG)));
        // the narrow family does not swallow anything
        assertTrue(families.swallowed(List.of(Family.REGISTRY)).isEmpty());
        // and it does not swallow itself
        assertTrue(families.swallowed(List.of(Family.CONFIG, Family.REGISTRY)).isEmpty());
    }

    @Test
    void swallowedIsEmptyForANestedSafeSelection() {
        TenantKeyFamilies families = TenantKeyFamilies.defaults();
        List<Family> runtime = List.of(Family.RUNTIME_STATE, Family.CHECKPOINT,
                Family.SINK_DEDUP, Family.CONTROL_PLANE);

        assertTrue(families.swallowed(runtime).isEmpty());
        assertTrue(families.swallowed(List.of(Family.values())).isEmpty());
    }

    @Test
    void swallowedRequiresStrictContainmentSoADuplicatePrefixIsLeftToTheMigrator() {
        // an override that collapses two families onto one prefix is a duplicate source
        // prefix, which the migrator rejects with its own message
        TenantKeyFamilies families = TenantKeyFamilies.defaults().with(
                Family.CONFIG, Family.REGISTRY.defaultPrefix());

        assertTrue(families.swallowed(List.of(Family.CONFIG, Family.REGISTRY)).isEmpty());
    }

    @Test
    void anOverriddenPrefixChangesWhoIsSwallowed() {
        // flattening the state prefix onto the checkpoint prefix narrows the swallow
        TenantKeyFamilies families = TenantKeyFamilies.defaults().with(
                Family.RUNTIME_STATE, "streaming:runtime:checkpoint:");

        assertTrue(families.swallowed(List.of(Family.RUNTIME_STATE)).isEmpty());
    }
}
