package io.github.cuihairu.redis.streaming.reliability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailedElementTest {

    @Test
    void testBasicCreation() {
        RuntimeException exception = new RuntimeException("Test error");
        FailedElement<String> failed = new FailedElement<>("test", exception, 3);

        assertEquals("test", failed.getElement());
        assertEquals(exception, failed.getException());
        assertEquals("Test error", failed.getErrorMessage());
        assertEquals(3, failed.getAttemptCount());
        assertTrue(failed.getTimestamp() > 0);
    }

    @Test
    void testGetStackTrace() {
        RuntimeException exception = new RuntimeException("Error");
        FailedElement<String> failed = new FailedElement<>("test", exception, 1);

        String stackTrace = failed.getStackTrace();
        assertNotNull(stackTrace);
        assertTrue(stackTrace.contains("RuntimeException"));
        assertTrue(stackTrace.contains("Error"));
    }

    @Test
    void testGetStackTraceWithNullException() {
        FailedElement<String> failed = new FailedElement<>("test", null, 1);

        String stackTrace = failed.getStackTrace();
        assertEquals("", stackTrace);
    }

    @Test
    void testCanRetry() {
        FailedElement<String> failed = new FailedElement<>("test", new RuntimeException(), 2);

        assertTrue(failed.canRetry(5));   // 2 < 5
        assertFalse(failed.canRetry(2));  // 2 >= 2
        assertFalse(failed.canRetry(1));  // 2 >= 1
    }

    @Test
    void testToString() {
        FailedElement<String> failed = new FailedElement<>(
                "test-element",
                new RuntimeException("Test error"),
                2
        );

        String str = failed.toString();
        assertTrue(str.contains("test-element"));
        assertTrue(str.contains("Test error"));
        assertTrue(str.contains("2"));
    }

    @Test
    void testNullErrorMessage() {
        Exception exception = new RuntimeException((String) null);
        FailedElement<String> failed = new FailedElement<>("test", exception, 1);

        // Should have a fallback error message
        assertNotNull(failed.getErrorMessage());
    }

    @Test
    void testTimestampIsRecent() {
        long before = System.currentTimeMillis();
        FailedElement<String> failed = new FailedElement<>("test", new RuntimeException(), 1);
        long after = System.currentTimeMillis();

        assertTrue(failed.getTimestamp() >= before);
        assertTrue(failed.getTimestamp() <= after);
    }
}
