package io.github.cuihairu.redis.streaming.reliability;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FailedElement
 */
class FailedElementTest {

    @Test
    void testConstructorWithAllFields() {
        // Given
        String element = "test-element";
        IOException exception = new IOException("Test error");
        int attemptCount = 3;

        // When
        FailedElement<String> failedElement = new FailedElement<>(element, exception, attemptCount);

        // Then
        assertEquals(element, failedElement.getElement());
        assertEquals(exception, failedElement.getException());
        assertEquals(attemptCount, failedElement.getAttemptCount());
        assertEquals("Test error", failedElement.getErrorMessage());
        assertTrue(failedElement.getTimestamp() > 0);
        assertTrue(failedElement.getTimestamp() <= System.currentTimeMillis());
    }

    @Test
    void testConstructorWithNullException() {
        // Given
        String element = "test-element";
        int attemptCount = 1;

        // When
        FailedElement<String> failedElement = new FailedElement<>(element, null, attemptCount);

        // Then
        assertEquals(element, failedElement.getElement());
        assertNull(failedElement.getException());
        assertEquals(attemptCount, failedElement.getAttemptCount());
        assertEquals("Unknown error", failedElement.getErrorMessage());
    }

    @Test
    void testConstructorWithEmptyMessageException() {
        // Given
        String element = "test-element";
        IOException exception = new IOException("");
        int attemptCount = 2;

        // When
        FailedElement<String> failedElement = new FailedElement<>(element, exception, attemptCount);

        // Then
        assertEquals("Unknown error", failedElement.getErrorMessage());
    }

    @Test
    void testGetStackTrace() {
        // Given
        String element = "test-element";
        IOException exception = new IOException("Test error");
        FailedElement<String> failedElement = new FailedElement<>(element, exception, 1);

        // When
        String stackTrace = failedElement.getStackTrace();

        // Then
        assertNotNull(stackTrace);
        assertTrue(stackTrace.contains("java.io.IOException: Test error"));
        assertTrue(stackTrace.contains("at "));
    }

    @Test
    void testGetStackTraceWithNullException() {
        // Given
        FailedElement<String> failedElement = new FailedElement<>("test", null, 1);

        // When
        String stackTrace = failedElement.getStackTrace();

        // Then
        assertEquals("", stackTrace);
    }

    @Test
    void testCanRetry() {
        // Given
        FailedElement<String> failedElement = new FailedElement<>("test", new IOException(), 2);

        // When & Then
        assertTrue(failedElement.canRetry(3));
        assertTrue(failedElement.canRetry(4));
        assertFalse(failedElement.canRetry(2));
        assertFalse(failedElement.canRetry(1));
    }

    @Test
    void testCanRetryWithZeroAttempts() {
        // Given
        FailedElement<String> failedElement = new FailedElement<>("test", new IOException(), 0);

        // When & Then
        assertTrue(failedElement.canRetry(1));
        assertFalse(failedElement.canRetry(0));
    }

    @Test
    void testToString() {
        // Given
        String element = "test-element";
        IOException exception = new IOException("Test error");
        FailedElement<String> failedElement = new FailedElement<>(element, exception, 3);

        // When
        String str = failedElement.toString();

        // Then
        assertTrue(str.contains("FailedElement{"));
        assertTrue(str.contains("element=" + element));
        assertTrue(str.contains("errorMessage='Test error'"));
        assertTrue(str.contains("attemptCount=3"));
        assertTrue(str.contains("timestamp="));
    }

    @Test
    void testToStringWithNullException() {
        // Given
        FailedElement<String> failedElement = new FailedElement<>("test", null, 1);

        // When
        String str = failedElement.toString();

        // Then
        assertTrue(str.contains("errorMessage='Unknown error'"));
    }

    @Test
    void testIsSerializable() {
        // Given
        String element = "test-element";
        IOException exception = new IOException("Test error");
        FailedElement<String> failedElement = new FailedElement<>(element, exception, 1);

        // When & Then
        assertTrue(failedElement instanceof java.io.Serializable);
    }

    @Test
    void testGetters() {
        // Given
        String element = "test-element";
        IOException exception = new IOException("Test error");
        int attemptCount = 5;
        FailedElement<String> failedElement = new FailedElement<>(element, exception, attemptCount);

        // When & Then
        assertEquals(element, failedElement.getElement());
        assertEquals(exception, failedElement.getException());
        assertEquals(attemptCount, failedElement.getAttemptCount());
        assertTrue(failedElement.getTimestamp() > 0);
        assertEquals("Test error", failedElement.getErrorMessage());
    }

    @Test
    void testGetErrorMessageWithNullException() {
        // Given
        FailedElement<String> failedElement = new FailedElement<>("test", null, 1);

        // When
        String errorMessage = failedElement.getErrorMessage();

        // Then
        assertEquals("Unknown error", errorMessage);
    }

    @Test
    void testGetErrorMessageWithComplexException() {
        // Given
        Exception exception = new RuntimeException("Root cause", new IOException("Wrapped error"));
        FailedElement<String> failedElement = new FailedElement<>("test", exception, 1);

        // When
        String errorMessage = failedElement.getErrorMessage();

        // Then
        assertEquals("Root cause", errorMessage);
    }

    @Test
    void testTimestampIsCapturedAtCreation() {
        // Given
        long before = System.currentTimeMillis();
        FailedElement<String> failedElement = new FailedElement<>("test", new IOException(), 1);
        long after = System.currentTimeMillis();

        // When & Then
        assertTrue(failedElement.getTimestamp() >= before);
        assertTrue(failedElement.getTimestamp() <= after);
    }

    @Test
    void testImmutableFields() {
        // Given
        FailedElement<String> failedElement = new FailedElement<>("test", new IOException(), 1);

        // When & Then - fields are final, cannot be modified
        // This test verifies the class design by checking that getters return consistent values
        String element1 = failedElement.getElement();
        String element2 = failedElement.getElement();
        assertEquals(element1, element2);
    }

    @Test
    void testWithDifferentElementTypes() {
        // Given & When
        FailedElement<Integer> intElement = new FailedElement<>(123, new IOException(), 1);
        FailedElement<String> stringElement = new FailedElement<>("test", new IOException(), 1);
        FailedElement<Object> objectElement = new FailedElement<>(new Object(), new IOException(), 1);

        // Then
        assertEquals(123, intElement.getElement());
        assertEquals("test", stringElement.getElement());
        assertNotNull(objectElement.getElement());
    }
}
