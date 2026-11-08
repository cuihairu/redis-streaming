package io.github.cuihairu.redis.streaming.join.operator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the Envelope accessors and the diagnostic {@link Object#toString()} contract.
 * The representation is what join diagnostics/logs surface, so the exact text is pinned.
 */
class EnvelopeTest {

    @Test
    void leftEnvelopeExposesSideKeyAndTimestamp() {
        Envelope<String, String, String> e = Envelope.forLeft("k1", 100L, "payload");
        assertEquals(Envelope.Side.LEFT, e.getSide());
        assertEquals("k1", e.getJoinKey());
        assertEquals(100L, e.getTimestamp());
        assertEquals("payload", e.getLeft());
        assertNull(e.getRight());
    }

    @Test
    void rightEnvelopeExposesSideKeyAndTimestamp() {
        Envelope<String, String, String> e = Envelope.forRight("k2", 200L, "payload");
        assertEquals(Envelope.Side.RIGHT, e.getSide());
        assertEquals("k2", e.getJoinKey());
        assertEquals(200L, e.getTimestamp());
        assertEquals("payload", e.getRight());
        assertNull(e.getLeft());
    }

    @Test
    void toStringPinsDiagnosticFormat() {
        Envelope<String, String, String> left = Envelope.forLeft("k1", 100L, "p");
        assertEquals("Envelope{LEFT, key=k1, ts=100}", left.toString());

        Envelope<String, String, String> right = Envelope.forRight("k2", 200L, "p");
        assertEquals("Envelope{RIGHT, key=k2, ts=200}", right.toString());
    }
}
