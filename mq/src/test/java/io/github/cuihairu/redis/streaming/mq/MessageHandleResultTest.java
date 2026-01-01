package io.github.cuihairu.redis.streaming.mq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MessageHandleResult
 */
class MessageHandleResultTest {

    @Test
    void testEnumValues() {
        assertEquals(4, MessageHandleResult.values().length);

        MessageHandleResult[] results = MessageHandleResult.values();
        assertTrue(java.util.Arrays.asList(results).contains(MessageHandleResult.SUCCESS));
        assertTrue(java.util.Arrays.asList(results).contains(MessageHandleResult.RETRY));
        assertTrue(java.util.Arrays.asList(results).contains(MessageHandleResult.FAIL));
        assertTrue(java.util.Arrays.asList(results).contains(MessageHandleResult.DEAD_LETTER));
    }

    @Test
    void testValueOf() {
        assertEquals(MessageHandleResult.SUCCESS, MessageHandleResult.valueOf("SUCCESS"));
        assertEquals(MessageHandleResult.RETRY, MessageHandleResult.valueOf("RETRY"));
        assertEquals(MessageHandleResult.FAIL, MessageHandleResult.valueOf("FAIL"));
        assertEquals(MessageHandleResult.DEAD_LETTER, MessageHandleResult.valueOf("DEAD_LETTER"));
    }

    @Test
    void testEnumConstants() {
        assertNotNull(MessageHandleResult.SUCCESS);
        assertNotNull(MessageHandleResult.RETRY);
        assertNotNull(MessageHandleResult.FAIL);
        assertNotNull(MessageHandleResult.DEAD_LETTER);
    }

    @Test
    void testEnumName() {
        assertEquals("SUCCESS", MessageHandleResult.SUCCESS.name());
        assertEquals("RETRY", MessageHandleResult.RETRY.name());
        assertEquals("FAIL", MessageHandleResult.FAIL.name());
        assertEquals("DEAD_LETTER", MessageHandleResult.DEAD_LETTER.name());
    }

    @Test
    void testEnumOrdinal() {
        assertEquals(0, MessageHandleResult.SUCCESS.ordinal());
        assertEquals(1, MessageHandleResult.RETRY.ordinal());
        assertEquals(2, MessageHandleResult.FAIL.ordinal());
        assertEquals(3, MessageHandleResult.DEAD_LETTER.ordinal());
    }

    @Test
    void testEnumEquality() {
        assertEquals(MessageHandleResult.SUCCESS, MessageHandleResult.SUCCESS);
        assertEquals(MessageHandleResult.RETRY, MessageHandleResult.RETRY);

        assertNotEquals(MessageHandleResult.SUCCESS, MessageHandleResult.RETRY);
        assertNotEquals(MessageHandleResult.FAIL, MessageHandleResult.DEAD_LETTER);
    }

    @Test
    void testEnumToString() {
        assertEquals("SUCCESS", MessageHandleResult.SUCCESS.toString());
        assertEquals("RETRY", MessageHandleResult.RETRY.toString());
        assertEquals("FAIL", MessageHandleResult.FAIL.toString());
        assertEquals("DEAD_LETTER", MessageHandleResult.DEAD_LETTER.toString());
    }

    @Test
    void testValueOfWithInvalidString() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> MessageHandleResult.valueOf("INVALID_RESULT"));

        assertTrue(exception.getMessage().contains("No enum constant"));
    }

    @Test
    void testValueOfWithNull() {
        assertThrows(NullPointerException.class,
                () -> MessageHandleResult.valueOf(null));
    }

    @Test
    void testEnumComparability() {
        // Enums implement Comparable
        assertTrue(MessageHandleResult.SUCCESS.compareTo(MessageHandleResult.RETRY) < 0);
        assertTrue(MessageHandleResult.RETRY.compareTo(MessageHandleResult.FAIL) < 0);
        assertTrue(MessageHandleResult.FAIL.compareTo(MessageHandleResult.DEAD_LETTER) < 0);

        assertEquals(0, MessageHandleResult.SUCCESS.compareTo(MessageHandleResult.SUCCESS));
        assertTrue(MessageHandleResult.DEAD_LETTER.compareTo(MessageHandleResult.SUCCESS) > 0);
    }

    @Test
    void testEnumClass() {
        assertEquals(MessageHandleResult.class, MessageHandleResult.SUCCESS.getClass());
        assertEquals(MessageHandleResult.class, MessageHandleResult.RETRY.getClass());
    }

    @Test
    void testGetDeclaringClass() {
        assertEquals(MessageHandleResult.class, MessageHandleResult.SUCCESS.getDeclaringClass());
        assertEquals(MessageHandleResult.class, MessageHandleResult.RETRY.getDeclaringClass());
    }

    @Test
    void testEnumOrdering() {
        // Verify the logical ordering of message handle results
        MessageHandleResult[] values = MessageHandleResult.values();
        assertEquals(MessageHandleResult.SUCCESS, values[0]);
        assertEquals(MessageHandleResult.RETRY, values[1]);
        assertEquals(MessageHandleResult.FAIL, values[2]);
        assertEquals(MessageHandleResult.DEAD_LETTER, values[3]);
    }

    @Test
    void testHashCode() {
        assertEquals(MessageHandleResult.SUCCESS.hashCode(), MessageHandleResult.SUCCESS.hashCode());
        assertNotEquals(MessageHandleResult.SUCCESS.hashCode(), MessageHandleResult.RETRY.hashCode());
    }
}
