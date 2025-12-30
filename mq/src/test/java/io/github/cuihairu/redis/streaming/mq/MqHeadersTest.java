package io.github.cuihairu.redis.streaming.mq;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MqHeaders constants
 */
class MqHeadersTest {

    // ===== Constant Values Tests =====

    @Test
    void testForcePartitionIdConstant() {
        assertEquals("x-force-partition-id", MqHeaders.FORCE_PARTITION_ID);
    }

    @Test
    void testPartitionIdConstant() {
        assertEquals("partitionId", MqHeaders.PARTITION_ID);
    }

    @Test
    void testPayloadMissingConstant() {
        assertEquals("x-payload-missing", MqHeaders.PAYLOAD_MISSING);
    }

    @Test
    void testPayloadMissingRefConstant() {
        assertEquals("x-payload-missing-ref", MqHeaders.PAYLOAD_MISSING_REF);
    }

    // ===== Constant Naming Tests =====

    @Test
    void testHeadersUseAsciiOnly() {
        // All headers should be ASCII-only
        assertTrue(isAscii(MqHeaders.FORCE_PARTITION_ID));
        assertTrue(isAscii(MqHeaders.PARTITION_ID));
        assertTrue(isAscii(MqHeaders.PAYLOAD_MISSING));
        assertTrue(isAscii(MqHeaders.PAYLOAD_MISSING_REF));
    }

    @Test
    void testHeadersAreNonEmpty() {
        assertFalse(MqHeaders.FORCE_PARTITION_ID.isEmpty());
        assertFalse(MqHeaders.PARTITION_ID.isEmpty());
        assertFalse(MqHeaders.PAYLOAD_MISSING.isEmpty());
        assertFalse(MqHeaders.PAYLOAD_MISSING_REF.isEmpty());
    }

    // ===== Header Prefix Tests =====

    @Test
    void testInternalHeadersUseXPrefix() {
        // Internal headers should use 'x-' prefix
        assertTrue(MqHeaders.FORCE_PARTITION_ID.startsWith("x-"));
        assertTrue(MqHeaders.PAYLOAD_MISSING.startsWith("x-"));
        assertTrue(MqHeaders.PAYLOAD_MISSING_REF.startsWith("x-"));
    }

    @Test
    void testPublicHeaderDoesNotUseXPrefix() {
        // Public/consumer header should not use 'x-' prefix
        assertFalse(MqHeaders.PARTITION_ID.startsWith("x-"));
    }

    // ===== Header Semantics Tests =====

    @Test
    void testForcePartitionIdSemantics() {
        // Force routing to a specific partition (int)
        String header = MqHeaders.FORCE_PARTITION_ID;
        assertTrue(header.contains("force"));
        assertTrue(header.contains("partition"));
    }

    @Test
    void testPartitionIdSemantics() {
        // Partition id hint placed into headers by consumers
        String header = MqHeaders.PARTITION_ID;
        assertTrue(header.contains("partition"));
        assertTrue(header.contains("Id"));
    }

    @Test
    void testPayloadMissingSemantics() {
        // Internal marker set when payload reference is missing
        String header = MqHeaders.PAYLOAD_MISSING;
        assertTrue(header.contains("payload"));
        assertTrue(header.contains("missing"));
    }

    @Test
    void testPayloadMissingRefSemantics() {
        // Internal header carrying the missing payload reference key
        String header = MqHeaders.PAYLOAD_MISSING_REF;
        assertTrue(header.contains("payload"));
        assertTrue(header.contains("missing"));
        assertTrue(header.contains("ref"));
    }

    // ===== Header Uniqueness Tests =====

    @Test
    void testAllHeadersAreUnique() {
        assertNotEquals(MqHeaders.FORCE_PARTITION_ID, MqHeaders.PARTITION_ID);
        assertNotEquals(MqHeaders.FORCE_PARTITION_ID, MqHeaders.PAYLOAD_MISSING);
        assertNotEquals(MqHeaders.FORCE_PARTITION_ID, MqHeaders.PAYLOAD_MISSING_REF);
        assertNotEquals(MqHeaders.PARTITION_ID, MqHeaders.PAYLOAD_MISSING);
        assertNotEquals(MqHeaders.PARTITION_ID, MqHeaders.PAYLOAD_MISSING_REF);
        assertNotEquals(MqHeaders.PAYLOAD_MISSING, MqHeaders.PAYLOAD_MISSING_REF);
    }

    // ===== Header Format Tests =====

    @Test
    void testHeadersUseKebabCase() {
        // Headers should use kebab-case (hyphens, not underscores or camelCase)
        assertTrue(MqHeaders.FORCE_PARTITION_ID.matches("^[a-z0-9-]+$"));
        // partitionId contains capital I - this is an exception
        assertTrue(MqHeaders.PAYLOAD_MISSING.matches("^[a-z0-9-]+$"));
        assertTrue(MqHeaders.PAYLOAD_MISSING_REF.matches("^[a-z0-9-]+$"));
    }

    @Test
    void testHeadersDoNotUseCamelCase() {
        // Most headers should not contain uppercase letters
        assertFalse(MqHeaders.FORCE_PARTITION_ID.matches(".*[A-Z].*"));
        assertFalse(MqHeaders.PAYLOAD_MISSING.matches(".*[A-Z].*"));
        assertFalse(MqHeaders.PAYLOAD_MISSING_REF.matches(".*[A-Z].*"));

        // partitionId is an exception - contains capital 'I'
        assertTrue(MqHeaders.PARTITION_ID.contains("I"));
    }

    // ===== Immutable Class Tests =====

    @Test
    void testClassIsFinal() {
        // Utility class should be final
        assertTrue(Modifier.isFinal(MqHeaders.class.getModifiers()));
    }

    @Test
    void testConstructorIsPrivate() throws Exception {
        // Utility class should have private constructor
        java.lang.reflect.Constructor<MqHeaders> constructor = MqHeaders.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));

        // Constructor should be accessible
        constructor.setAccessible(true);
        MqHeaders instance = constructor.newInstance();
        assertNotNull(instance);
    }

    // ===== Helper Methods =====

    private boolean isAscii(String str) {
        return str.chars().allMatch(c -> c < 128);
    }
}
