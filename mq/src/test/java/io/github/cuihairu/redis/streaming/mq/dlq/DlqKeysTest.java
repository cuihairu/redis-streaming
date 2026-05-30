package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DlqKeys key generation utilities.
 */
class DlqKeysTest {

    private static final String DEFAULT_PREFIX = "stream:topic";

    @AfterEach
    void resetConfiguration() {
        // Reset to default after each test to avoid test interference
        DlqKeys.configure(DEFAULT_PREFIX);
    }

    @Test
    void dlq_withDefaultPrefix_returnsCorrectKey() {
        // When
        String result = DlqKeys.dlq("test-topic");

        // Then
        assertEquals("stream:topic:test-topic:dlq", result);
    }

    @Test
    void dlq_withEmptyTopic_returnsCorrectKey() {
        // When
        String result = DlqKeys.dlq("");

        // Then
        assertEquals("stream:topic::dlq", result);
    }

    @Test
    void dlq_withSpecialCharactersInTopic_returnsCorrectKey() {
        // When
        String result = DlqKeys.dlq("test.topic-with_special:chars");

        // Then
        assertEquals("stream:topic:test.topic-with_special:chars:dlq", result);
    }

    @Test
    void dlq_withNumbersInTopic_returnsCorrectKey() {
        // When
        String result = DlqKeys.dlq("topic123");

        // Then
        assertEquals("stream:topic:topic123:dlq", result);
    }

    @Test
    void dlq_withMultipleColonsInTopic_preservesColons() {
        // When
        String result = DlqKeys.dlq("namespace:topic:subtopic");

        // Then
        assertEquals("stream:topic:namespace:topic:subtopic:dlq", result);
    }

    @Test
    void configure_withValidPrefix_updatesPrefix() {
        // When
        DlqKeys.configure("custom:prefix");
        String result = DlqKeys.dlq("test-topic");

        // Then
        assertEquals("custom:prefix:test-topic:dlq", result);
    }

    @Test
    void configure_withEmptyPrefix_ignoresUpdate() {
        // Given
        String originalPrefix = "stream:topic";

        // When
        DlqKeys.configure("");
        String result = DlqKeys.dlq("test-topic");

        // Then
        assertEquals(originalPrefix + ":test-topic:dlq", result);
    }

    @Test
    void configure_withBlankPrefix_ignoresUpdate() {
        // Given
        String originalPrefix = "stream:topic";

        // When
        DlqKeys.configure("   ");
        String result = DlqKeys.dlq("test-topic");

        // Then
        assertEquals(originalPrefix + ":test-topic:dlq", result);
    }

    @Test
    void configure_withNullPrefix_ignoresUpdate() {
        // Given
        String originalPrefix = "stream:topic";

        // When
        DlqKeys.configure(null);
        String result = DlqKeys.dlq("test-topic");

        // Then
        assertEquals(originalPrefix + ":test-topic:dlq", result);
    }

    @Test
    void configure_withCustomPrefixAndMultipleDlqCalls_usesConsistentPrefix() {
        // When
        DlqKeys.configure("myapp:stream");
        String result1 = DlqKeys.dlq("topic1");
        String result2 = DlqKeys.dlq("topic2");

        // Then
        assertEquals("myapp:stream:topic1:dlq", result1);
        assertEquals("myapp:stream:topic2:dlq", result2);
    }

    @Test
    void configure_withPrefixEndingWithColon_preservesStructure() {
        // When
        DlqKeys.configure("custom:");
        String result = DlqKeys.dlq("test-topic");

        // Then
        assertEquals("custom::test-topic:dlq", result);
    }

    @Test
    void configure_withPrefixWithoutColon_worksCorrectly() {
        // When
        DlqKeys.configure("myprefix");
        String result = DlqKeys.dlq("test-topic");

        // Then
        assertEquals("myprefix:test-topic:dlq", result);
    }

    @Test
    void dlq_afterMultipleConfigureChanges_usesLatestPrefix() {
        // When
        DlqKeys.configure("prefix1");
        DlqKeys.configure("prefix2");
        String result = DlqKeys.dlq("test-topic");

        // Then
        assertEquals("prefix2:test-topic:dlq", result);
    }
}
