package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JoinConfig
 */
class JoinConfigTest {

    @Test
    void testBuilderWithAllFields() {
        // Given
        Function<String, String> leftKeySelector = s -> s.split(":")[0];
        Function<String, String> rightKeySelector = s -> s.split(":")[0];
        Function<String, Long> leftTimestampExtractor = Long::parseLong;
        Function<String, Long> rightTimestampExtractor = Long::parseLong;
        JoinWindow window = JoinWindow.of(Duration.ofSeconds(5), Duration.ofSeconds(5));

        // When
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(window)
                .leftKeySelector(leftKeySelector)
                .rightKeySelector(rightKeySelector)
                .leftTimestampExtractor(leftTimestampExtractor)
                .rightTimestampExtractor(rightTimestampExtractor)
                .maxStateSize(5000)
                .stateRetentionTime(1800000)
                .build();

        // Then
        assertEquals(JoinType.INNER, config.getJoinType());
        assertEquals(window, config.getJoinWindow());
        assertEquals(leftKeySelector, config.getLeftKeySelector());
        assertEquals(rightKeySelector, config.getRightKeySelector());
        assertEquals(leftTimestampExtractor, config.getLeftTimestampExtractor());
        assertEquals(rightTimestampExtractor, config.getRightTimestampExtractor());
        assertEquals(5000, config.getMaxStateSize());
        assertEquals(1800000, config.getStateRetentionTime());
    }

    @Test
    void testBuilderWithDefaults() {
        // Given
        Function<String, String> leftKeySelector = Function.identity();
        Function<String, String> rightKeySelector = Function.identity();
        JoinWindow window = JoinWindow.ofSize(Duration.ofMinutes(1));

        // When
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.LEFT)
                .joinWindow(window)
                .leftKeySelector(leftKeySelector)
                .rightKeySelector(rightKeySelector)
                .build();

        // Then - should have default values
        assertEquals(JoinType.LEFT, config.getJoinType());
        assertEquals(window, config.getJoinWindow());
        assertEquals(10000, config.getMaxStateSize());
        assertEquals(3600000, config.getStateRetentionTime());
    }

    @Test
    void testValidateWithValidConfig() {
        // Given
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Function.identity())
                .rightKeySelector(Function.identity())
                .maxStateSize(1000)
                .stateRetentionTime(60000)
                .build();

        // When & Then - should not throw
        assertDoesNotThrow(() -> config.validate());
    }

    @Test
    void testValidateWithNullJoinType() {
        // Given
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Function.identity())
                .rightKeySelector(Function.identity())
                .build();

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("Join type must be specified"));
    }

    @Test
    void testValidateWithNullJoinWindow() {
        // Given
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .leftKeySelector(Function.identity())
                .rightKeySelector(Function.identity())
                .build();

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("Join window must be specified"));
    }

    @Test
    void testValidateWithNullLeftKeySelector() {
        // Given
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .rightKeySelector(Function.identity())
                .build();

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("Left key selector must be specified"));
    }

    @Test
    void testValidateWithNullRightKeySelector() {
        // Given
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Function.identity())
                .build();

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("Right key selector must be specified"));
    }

    @Test
    void testValidateWithNegativeMaxStateSize() {
        // Given
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Function.identity())
                .rightKeySelector(Function.identity())
                .maxStateSize(-100)
                .build();

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("Max state size must be positive"));
    }

    @Test
    void testValidateWithZeroMaxStateSize() {
        // Given
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Function.identity())
                .rightKeySelector(Function.identity())
                .maxStateSize(0)
                .build();

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("Max state size must be positive"));
    }

    @Test
    void testValidateWithNegativeStateRetentionTime() {
        // Given
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Function.identity())
                .rightKeySelector(Function.identity())
                .stateRetentionTime(-1000)
                .build();

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("State retention time must be positive"));
    }

    @Test
    void testValidateWithZeroStateRetentionTime() {
        // Given
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Function.identity())
                .rightKeySelector(Function.identity())
                .stateRetentionTime(0)
                .build();

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("State retention time must be positive"));
    }

    @Test
    void testInnerJoinFactoryMethod() {
        // Given
        Function<String, String> leftKeySelector = s -> s.split(":")[0];
        Function<String, String> rightKeySelector = s -> s.split(":")[0];
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(30));

        // When
        JoinConfig<String, String, String> config = JoinConfig.innerJoin(leftKeySelector, rightKeySelector, window);

        // Then
        assertEquals(JoinType.INNER, config.getJoinType());
        assertEquals(window, config.getJoinWindow());
        assertEquals(leftKeySelector, config.getLeftKeySelector());
        assertEquals(rightKeySelector, config.getRightKeySelector());
    }

    @Test
    void testLeftJoinFactoryMethod() {
        // Given
        Function<Integer, String> leftKeySelector = Object::toString;
        Function<Integer, String> rightKeySelector = Object::toString;
        JoinWindow window = JoinWindow.ofSize(Duration.ofMinutes(5));

        // When
        JoinConfig<Integer, Integer, String> config = JoinConfig.leftJoin(leftKeySelector, rightKeySelector, window);

        // Then
        assertEquals(JoinType.LEFT, config.getJoinType());
        assertEquals(window, config.getJoinWindow());
        assertEquals(leftKeySelector, config.getLeftKeySelector());
        assertEquals(rightKeySelector, config.getRightKeySelector());
    }

    @Test
    void testGetters() {
        // Given
        Function<String, Integer> leftKeySelector = String::length;
        Function<String, Integer> rightKeySelector = String::hashCode;
        JoinWindow window = JoinWindow.afterOnly(Duration.ofSeconds(10));

        JoinConfig<String, String, Integer> config = JoinConfig.<String, String, Integer>builder()
                .joinType(JoinType.RIGHT)
                .joinWindow(window)
                .leftKeySelector(leftKeySelector)
                .rightKeySelector(rightKeySelector)
                .maxStateSize(20000)
                .stateRetentionTime(7200000)
                .build();

        // When & Then
        assertEquals(JoinType.RIGHT, config.getJoinType());
        assertEquals(window, config.getJoinWindow());
        assertEquals(leftKeySelector, config.getLeftKeySelector());
        assertEquals(rightKeySelector, config.getRightKeySelector());
        assertEquals(20000, config.getMaxStateSize());
        assertEquals(7200000, config.getStateRetentionTime());
        assertNull(config.getLeftTimestampExtractor());
        assertNull(config.getRightTimestampExtractor());
    }

    @Test
    void testDifferentJoinTypes() {
        // Given
        JoinWindow window = JoinWindow.beforeOnly(Duration.ofSeconds(5));

        // When & Then - all join types should work
        JoinConfig<String, String, String> inner = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER).joinWindow(window)
                .leftKeySelector(Function.identity()).rightKeySelector(Function.identity()).build();
        assertEquals(JoinType.INNER, inner.getJoinType());

        JoinConfig<String, String, String> left = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.LEFT).joinWindow(window)
                .leftKeySelector(Function.identity()).rightKeySelector(Function.identity()).build();
        assertEquals(JoinType.LEFT, left.getJoinType());

        JoinConfig<String, String, String> right = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.RIGHT).joinWindow(window)
                .leftKeySelector(Function.identity()).rightKeySelector(Function.identity()).build();
        assertEquals(JoinType.RIGHT, right.getJoinType());

        JoinConfig<String, String, String> fullOuter = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.FULL_OUTER).joinWindow(window)
                .leftKeySelector(Function.identity()).rightKeySelector(Function.identity()).build();
        assertEquals(JoinType.FULL_OUTER, fullOuter.getJoinType());
    }

    @Test
    void testIsSerializable() {
        // Given
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Function.identity())
                .rightKeySelector(Function.identity())
                .build();

        // When & Then
        assertTrue(config instanceof java.io.Serializable);
    }

    @Test
    void testToString() {
        // Given
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(5)))
                .leftKeySelector(Function.identity())
                .rightKeySelector(Function.identity())
                .build();

        // When
        String str = config.toString();

        // Then - Lombok @Data should generate toString
        assertNotNull(str);
        assertTrue(str.contains("JoinConfig"));
    }

    @Test
    void testEqualsAndHashCode() {
        // Given
        Function<String, String> keySelector = Function.identity();
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(10));

        JoinConfig<String, String, String> config1 = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(window)
                .leftKeySelector(keySelector)
                .rightKeySelector(keySelector)
                .build();

        JoinConfig<String, String, String> config2 = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(window)
                .leftKeySelector(keySelector)
                .rightKeySelector(keySelector)
                .build();

        JoinConfig<String, String, String> config3 = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.LEFT)
                .joinWindow(window)
                .leftKeySelector(keySelector)
                .rightKeySelector(keySelector)
                .build();

        // Then
        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
        assertNotEquals(config1, config3);
    }

    @Test
    void testWithLargeStateSize() {
        // Given & When
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Function.identity())
                .rightKeySelector(Function.identity())
                .maxStateSize(Integer.MAX_VALUE)
                .build();

        // Then
        assertEquals(Integer.MAX_VALUE, config.getMaxStateSize());
        assertDoesNotThrow(() -> config.validate());
    }

    @Test
    void testWithLongStateRetentionTime() {
        // Given & When
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Function.identity())
                .rightKeySelector(Function.identity())
                .stateRetentionTime(Long.MAX_VALUE)
                .build();

        // Then
        assertEquals(Long.MAX_VALUE, config.getStateRetentionTime());
        assertDoesNotThrow(() -> config.validate());
    }

    @Test
    void testComplexKeySelectors() {
        // Given - complex key extraction logic
        Function<String, String> leftKeySelector = s -> {
            String[] parts = s.split("\\|");
            return parts[0] + ":" + parts[1];
        };
        Function<Integer, String> rightKeySelector = i -> "key:" + (i % 100);

        // When
        JoinConfig<String, Integer, String> config = JoinConfig.<String, Integer, String>builder()
                .joinType(JoinType.LEFT)
                .joinWindow(JoinWindow.ofSize(Duration.ofMinutes(1)))
                .leftKeySelector(leftKeySelector)
                .rightKeySelector(rightKeySelector)
                .build();

        // Then
        assertEquals("key:10:value", leftKeySelector.apply("key:10|value|data"));
        assertEquals("key:50", rightKeySelector.apply(150));
        assertDoesNotThrow(() -> config.validate());
    }
}
