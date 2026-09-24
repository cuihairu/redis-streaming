package io.github.cuihairu.redis.streaming.registry.keys;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers RegistryKeys.sanitizeServiceName null path, isServiceNameSafe and the
 * extract* wrong-format fallbacks.
 */
class RegistryKeysCoverageTest {

    @Test
    void sanitizeServiceNameHandlesNull() {
        assertNull(RegistryKeys.sanitizeServiceName(null));
        assertEquals("a_b-c-d-e-f", RegistryKeys.sanitizeServiceName("a:b c\td\ne\rf"));
    }

    @Test
    void isServiceNameSafeChecksEveryForbiddenCharacter() {
        assertFalse(RegistryKeys.isServiceNameSafe(null));
        assertFalse(RegistryKeys.isServiceNameSafe("a:b"));
        assertFalse(RegistryKeys.isServiceNameSafe("a b"));
        assertFalse(RegistryKeys.isServiceNameSafe("a\tb"));
        assertFalse(RegistryKeys.isServiceNameSafe("a\nb"));
        assertFalse(RegistryKeys.isServiceNameSafe("a\rb"));
        assertTrue(RegistryKeys.isServiceNameSafe("safe-name_1"));
    }

    @Test
    void extractorsReturnNullForMalformedKeys() {
        RegistryKeys keys = new RegistryKeys("registry");

        // well-formed keys still work
        assertEquals("svc", keys.extractServiceNameFromInstanceKey("registry:services:svc:instance:i1"));
        assertEquals("i1", keys.extractInstanceIdFromInstanceKey("registry:services:svc:instance:i1"));
        assertEquals("svc", keys.extractServiceNameFromHeartbeatsKey("registry:services:svc:heartbeats"));

        // wrong shapes -> null
        assertNull(keys.extractServiceNameFromInstanceKey("registry:services:svc:heartbeats"));
        assertNull(keys.extractInstanceIdFromInstanceKey("registry:services:svc:instance"));
        assertNull(keys.extractServiceNameFromHeartbeatsKey("registry:services:svc:instance:i1"));
        assertNull(keys.extractServiceNameFromInstanceKey("other:services:svc:instance:i1"));
    }
}
