package io.github.cuihairu.redis.streaming.registry.heartbeat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for UpdateDecision
 */
class UpdateDecisionTest {

    @Test
    void testEnumValuesCount() {
        // Given & When
        UpdateDecision[] values = UpdateDecision.values();

        // Then
        assertEquals(5, values.length);
    }

    @Test
    void testEnumValuesContainsAllDecisions() {
        // Given & When
        UpdateDecision[] values = UpdateDecision.values();

        // Then
        assertTrue(java.util.Arrays.asList(values).contains(UpdateDecision.NO_UPDATE));
        assertTrue(java.util.Arrays.asList(values).contains(UpdateDecision.HEARTBEAT_ONLY));
        assertTrue(java.util.Arrays.asList(values).contains(UpdateDecision.METRICS_UPDATE));
        assertTrue(java.util.Arrays.asList(values).contains(UpdateDecision.METADATA_UPDATE));
        assertTrue(java.util.Arrays.asList(values).contains(UpdateDecision.FULL_UPDATE));
    }

    @Test
    void testValueOfNoUpdate() {
        // Given & When
        UpdateDecision decision = UpdateDecision.valueOf("NO_UPDATE");

        // Then
        assertSame(UpdateDecision.NO_UPDATE, decision);
    }

    @Test
    void testValueOfHeartbeatOnly() {
        // Given & When
        UpdateDecision decision = UpdateDecision.valueOf("HEARTBEAT_ONLY");

        // Then
        assertSame(UpdateDecision.HEARTBEAT_ONLY, decision);
    }

    @Test
    void testValueOfMetricsUpdate() {
        // Given & When
        UpdateDecision decision = UpdateDecision.valueOf("METRICS_UPDATE");

        // Then
        assertSame(UpdateDecision.METRICS_UPDATE, decision);
    }

    @Test
    void testValueOfMetadataUpdate() {
        // Given & When
        UpdateDecision decision = UpdateDecision.valueOf("METADATA_UPDATE");

        // Then
        assertSame(UpdateDecision.METADATA_UPDATE, decision);
    }

    @Test
    void testValueOfFullUpdate() {
        // Given & When
        UpdateDecision decision = UpdateDecision.valueOf("FULL_UPDATE");

        // Then
        assertSame(UpdateDecision.FULL_UPDATE, decision);
    }

    @Test
    void testValueOfInvalidValueThrowsException() {
        // Given & When & Then
        assertThrows(IllegalArgumentException.class, () -> UpdateDecision.valueOf("INVALID"));
    }

    @Test
    void testEnumOrdering() {
        // Given & When
        UpdateDecision[] values = UpdateDecision.values();

        // Then - verify the order is as declared
        assertEquals(UpdateDecision.NO_UPDATE, values[0]);
        assertEquals(UpdateDecision.HEARTBEAT_ONLY, values[1]);
        assertEquals(UpdateDecision.METRICS_UPDATE, values[2]);
        assertEquals(UpdateDecision.METADATA_UPDATE, values[3]);
        assertEquals(UpdateDecision.FULL_UPDATE, values[4]);
    }

    @Test
    void testEnumIsFinal() {
        // Given & When & Then - enums are implicitly final
        assertTrue(java.lang.reflect.Modifier.isFinal(UpdateDecision.class.getModifiers()));
    }

    @Test
    void testNoUpdateSemantic() {
        // Given & When
        UpdateDecision decision = UpdateDecision.NO_UPDATE;

        // Then - represents no update needed
        assertNotNull(decision);
        assertEquals("NO_UPDATE", decision.name());
    }

    @Test
    void testHeartbeatOnlySemantic() {
        // Given & When
        UpdateDecision decision = UpdateDecision.HEARTBEAT_ONLY;

        // Then - represents heartbeat timestamp update only
        assertNotNull(decision);
        assertEquals("HEARTBEAT_ONLY", decision.name());
    }

    @Test
    void testMetricsUpdateSemantic() {
        // Given & When
        UpdateDecision decision = UpdateDecision.METRICS_UPDATE;

        // Then - represents metrics + heartbeat update
        assertNotNull(decision);
        assertEquals("METRICS_UPDATE", decision.name());
    }

    @Test
    void testMetadataUpdateSemantic() {
        // Given & When
        UpdateDecision decision = UpdateDecision.METADATA_UPDATE;

        // Then - represents metadata + heartbeat update
        assertNotNull(decision);
        assertEquals("METADATA_UPDATE", decision.name());
    }

    @Test
    void testFullUpdateSemantic() {
        // Given & When
        UpdateDecision decision = UpdateDecision.FULL_UPDATE;

        // Then - represents metadata + metrics + heartbeat update
        assertNotNull(decision);
        assertEquals("FULL_UPDATE", decision.name());
    }

    @Test
    void testEnumConstantsAreSingletons() {
        // Given & When
        UpdateDecision decision1 = UpdateDecision.NO_UPDATE;
        UpdateDecision decision2 = UpdateDecision.valueOf("NO_UPDATE");

        // Then - enum constants are singletons
        assertSame(decision1, decision2);
    }

    @Test
    void testAllDecisionsAreComparable() {
        // Given & When
        UpdateDecision decision1 = UpdateDecision.HEARTBEAT_ONLY;
        UpdateDecision decision2 = UpdateDecision.METRICS_UPDATE;

        // Then - enums implement Comparable
        assertNotNull(decision1.compareTo(decision2));
        assertTrue(decision1.compareTo(decision2) < 0);
    }

    @Test
    void testEnumCanBeUsedInSwitch() {
        // Given
        UpdateDecision decision = UpdateDecision.METRICS_UPDATE;
        int result = 0;

        // When
        switch (decision) {
            case NO_UPDATE:
                result = 1;
                break;
            case HEARTBEAT_ONLY:
                result = 2;
                break;
            case METRICS_UPDATE:
                result = 3;
                break;
            case METADATA_UPDATE:
                result = 4;
                break;
            case FULL_UPDATE:
                result = 5;
                break;
        }

        // Then
        assertEquals(3, result);
    }

    @Test
    void testEnumCanBeIterated() {
        // Given & When
        int count = 0;
        for (UpdateDecision decision : UpdateDecision.values()) {
            assertNotNull(decision);
            count++;
        }

        // Then
        assertEquals(5, count);
    }

    @Test
    void testEnumOrdinal() {
        // Given & When
        assertEquals(0, UpdateDecision.NO_UPDATE.ordinal());
        assertEquals(1, UpdateDecision.HEARTBEAT_ONLY.ordinal());
        assertEquals(2, UpdateDecision.METRICS_UPDATE.ordinal());
        assertEquals(3, UpdateDecision.METADATA_UPDATE.ordinal());
        assertEquals(4, UpdateDecision.FULL_UPDATE.ordinal());

        // Then - ordinal starts at 0
    }

    @Test
    void testEnumInCollection() {
        // Given
        java.util.Set<UpdateDecision> decisions = java.util.EnumSet.noneOf(UpdateDecision.class);

        // When
        decisions.add(UpdateDecision.NO_UPDATE);
        decisions.add(UpdateDecision.HEARTBEAT_ONLY);
        decisions.add(UpdateDecision.METRICS_UPDATE);

        // Then
        assertEquals(3, decisions.size());
        assertTrue(decisions.contains(UpdateDecision.NO_UPDATE));
        assertFalse(decisions.contains(UpdateDecision.METADATA_UPDATE));
    }

    @Test
    void testEnumCanBeUsedAsMapKey() {
        // Given
        java.util.Map<UpdateDecision, String> descriptions = new java.util.EnumMap<>(UpdateDecision.class);

        // When
        descriptions.put(UpdateDecision.NO_UPDATE, "No update needed");
        descriptions.put(UpdateDecision.HEARTBEAT_ONLY, "Heartbeat only");
        descriptions.put(UpdateDecision.FULL_UPDATE, "Full update");

        // Then
        assertEquals("No update needed", descriptions.get(UpdateDecision.NO_UPDATE));
        assertEquals("Heartbeat only", descriptions.get(UpdateDecision.HEARTBEAT_ONLY));
        assertEquals("Full update", descriptions.get(UpdateDecision.FULL_UPDATE));
        assertNull(descriptions.get(UpdateDecision.METADATA_UPDATE));
    }
}
