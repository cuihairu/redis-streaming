package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link RedisDeadLetterAdmin#listTopics}: DLQ-shaped keys with
 * an empty topic segment must be skipped.
 */
class RedisDeadLetterAdminSprintCoverageTest {

    private RedissonClient client;
    private RKeys keys;

    @BeforeEach
    void setUp() throws Exception {
        DlqKeys.configure("stream:topic");
        client = mock(RedissonClient.class);
        keys = mock(RKeys.class);
        when(client.getKeys()).thenReturn(keys);
    }

    @Test
    void listTopicsSkipsEmptyTopicSegment() throws Exception {
        when(keys.getKeys()).thenReturn(List.of(
                "stream:topic::dlq",
                "stream:topic:alpha:dlq",
                "stream:topic:beta:dlq"));

        List<String> topics = new RedisDeadLetterAdmin(client, mock(DeadLetterService.class)).listTopics();
        assertEquals(List.of("alpha", "beta"), topics);
    }
}
