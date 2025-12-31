package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class JoinConfigConvenienceTest {

    @Test
    void innerJoinFactoryBuildsValidConfig() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(5));
        JoinConfig<String, Integer, String> config = JoinConfig.innerJoin(s -> s, i -> String.valueOf(i), window);

        assertEquals(JoinType.INNER, config.getJoinType());
        assertEquals(window, config.getJoinWindow());
        assertNotNull(config.getLeftKeySelector());
        assertNotNull(config.getRightKeySelector());
        config.validate();
    }

    @Test
    void leftJoinFactoryBuildsValidConfig() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(5));
        JoinConfig<String, Integer, String> config = JoinConfig.leftJoin(s -> s, i -> String.valueOf(i), window);

        assertEquals(JoinType.LEFT, config.getJoinType());
        assertEquals(window, config.getJoinWindow());
        config.validate();
    }

    @Test
    void validateRejectsNonPositiveRetentionAndStateSize() {
        JoinConfig<String, String, String> badSize = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(1)))
                .leftKeySelector(s -> s)
                .rightKeySelector(s -> s)
                .maxStateSize(0)
                .build();
        assertThrows(IllegalArgumentException.class, badSize::validate);

        JoinConfig<String, String, String> badRetention = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(1)))
                .leftKeySelector(s -> s)
                .rightKeySelector(s -> s)
                .stateRetentionTime(0)
                .build();
        assertThrows(IllegalArgumentException.class, badRetention::validate);
    }

    @Test
    void fullOuterJoinWithBuilder() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofMinutes(1));
        JoinConfig<String, Integer, String> config = JoinConfig.<String, Integer, String>builder()
                .joinType(JoinType.FULL_OUTER)
                .joinWindow(window)
                .leftKeySelector(s -> s)
                .rightKeySelector(i -> String.valueOf(i))
                .build();

        assertEquals(JoinType.FULL_OUTER, config.getJoinType());
        config.validate();
    }

    @Test
    void rightJoinWithBuilder() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(30));
        JoinConfig<String, Integer, String> config = JoinConfig.<String, Integer, String>builder()
                .joinType(JoinType.RIGHT)
                .joinWindow(window)
                .leftKeySelector(s -> s)
                .rightKeySelector(i -> String.valueOf(i))
                .build();

        assertEquals(JoinType.RIGHT, config.getJoinType());
        config.validate();
    }

    @Test
    void innerJoinFactoryWithDifferentTypes() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(10));
        JoinConfig<Integer, String, String> config = JoinConfig.innerJoin(
                i -> i.toString(),
                s -> s,
                window
        );

        assertEquals(JoinType.INNER, config.getJoinType());
        config.validate();
    }

    @Test
    void leftJoinWithComplexKeySelectors() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(5));

        class Person {
            String department;
            String name;
        }

        class Department {
            String id;
            String name;
        }

        JoinConfig<Person, Department, String> config = JoinConfig.leftJoin(
                p -> p.department,
                d -> d.id,
                window
        );

        assertEquals(JoinType.LEFT, config.getJoinType());
        assertNotNull(config.getLeftKeySelector());
        assertNotNull(config.getRightKeySelector());
        config.validate();
    }

    @Test
    void innerJoinWithZeroMaxStateSizeThrowsException() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(1));

        // Build with invalid state size
        JoinConfig<String, String, String> badConfig = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(window)
                .leftKeySelector(s -> s)
                .rightKeySelector(s -> s)
                .maxStateSize(-1)
                .build();

        assertThrows(IllegalArgumentException.class, badConfig::validate);
    }

    @Test
    void leftJoinWithNegativeRetentionTimeThrowsException() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(1));
        JoinConfig<String, String, String> badConfig = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.LEFT)
                .joinWindow(window)
                .leftKeySelector(s -> s)
                .rightKeySelector(s -> s)
                .stateRetentionTime(-100)
                .build();

        assertThrows(IllegalArgumentException.class, badConfig::validate);
    }

    @Test
    void fullOuterJoinWithValidStateSize() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(10));
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.FULL_OUTER)
                .joinWindow(window)
                .leftKeySelector(s -> s)
                .rightKeySelector(s -> s)
                .maxStateSize(1000)
                .stateRetentionTime(5000)
                .build();

        assertEquals(JoinType.FULL_OUTER, config.getJoinType());
        assertEquals(1000, config.getMaxStateSize());
        assertEquals(5000, config.getStateRetentionTime());
        config.validate();
    }

    @Test
    void innerJoinWithMinimalConfig() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofMillis(100));
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(window)
                .leftKeySelector(s -> s)
                .rightKeySelector(s -> s)
                .maxStateSize(1)
                .stateRetentionTime(1)
                .build();

        assertEquals(JoinType.INNER, config.getJoinType());
        assertEquals(1, config.getMaxStateSize());
        assertEquals(1, config.getStateRetentionTime());
        config.validate();
    }

    @Test
    void leftJoinWithLargeConfig() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofDays(1));
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.LEFT)
                .joinWindow(window)
                .leftKeySelector(s -> s)
                .rightKeySelector(s -> s)
                .maxStateSize(100000)
                .stateRetentionTime(86400000)
                .build();

        assertEquals(JoinType.LEFT, config.getJoinType());
        assertEquals(100000, config.getMaxStateSize());
        assertEquals(86400000, config.getStateRetentionTime());
        config.validate();
    }

    @Test
    void validateWithNullJoinTypeThrowsException() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(1));
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(null)
                .joinWindow(window)
                .leftKeySelector(s -> s)
                .rightKeySelector(s -> s)
                .build();

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void validateWithNullJoinWindowThrowsException() {
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(null)
                .leftKeySelector(s -> s)
                .rightKeySelector(s -> s)
                .build();

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void validateWithNullKeySelectorsThrowsException() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(1));
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(window)
                .leftKeySelector(null)
                .rightKeySelector(s -> s)
                .build();

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void validateWithNullRightKeySelectorThrowsException() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(1));
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(window)
                .leftKeySelector(s -> s)
                .rightKeySelector(null)
                .build();

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void rightJoinFactoryBuildsValidConfig() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(30));
        JoinConfig<String, Integer, String> config = JoinConfig.<String, Integer, String>builder()
                .joinType(JoinType.RIGHT)
                .joinWindow(window)
                .leftKeySelector(s -> s)
                .rightKeySelector(i -> String.valueOf(i))
                .build();

        assertEquals(JoinType.RIGHT, config.getJoinType());
        assertEquals(window, config.getJoinWindow());
        config.validate();
    }

    @Test
    void allJoinTypesAreValid() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(1));

        // Verify all join types are valid
        assertDoesNotThrow(() -> {
            JoinConfig.<String, String, String>builder()
                    .joinType(JoinType.INNER)
                    .joinWindow(window)
                    .leftKeySelector(s -> s)
                    .rightKeySelector(s -> s)
                    .build()
                    .validate();
        });

        assertDoesNotThrow(() -> {
            JoinConfig.<String, String, String>builder()
                    .joinType(JoinType.LEFT)
                    .joinWindow(window)
                    .leftKeySelector(s -> s)
                    .rightKeySelector(s -> s)
                    .build()
                    .validate();
        });

        assertDoesNotThrow(() -> {
            JoinConfig.<String, String, String>builder()
                    .joinType(JoinType.RIGHT)
                    .joinWindow(window)
                    .leftKeySelector(s -> s)
                    .rightKeySelector(s -> s)
                    .build()
                    .validate();
        });

        assertDoesNotThrow(() -> {
            JoinConfig.<String, String, String>builder()
                    .joinType(JoinType.FULL_OUTER)
                    .joinWindow(window)
                    .leftKeySelector(s -> s)
                    .rightKeySelector(s -> s)
                    .build()
                    .validate();
        });
    }
}

