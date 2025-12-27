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
}

