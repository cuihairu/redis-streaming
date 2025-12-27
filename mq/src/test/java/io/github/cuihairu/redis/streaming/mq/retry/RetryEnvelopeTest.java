package io.github.cuihairu.redis.streaming.mq.retry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class RetryEnvelopeTest {

    @Test
    public void testGettersSettersAndCtor() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of("h", "v"), 2, 3, "id");
        assertEquals("t", env.getTopic());
        assertEquals(1, env.getPartitionId());
        assertEquals("p", env.getPayload());
        assertEquals("k", env.getKey());
        assertEquals(Map.of("h", "v"), env.getHeaders());
        assertEquals(2, env.getRetryCount());
        assertEquals(3, env.getMaxRetries());
        assertEquals("id", env.getOriginalMessageId());

        env.setTopic("t2");
        env.setPartitionId(2);
        env.setPayload("p2");
        env.setKey("k2");
        env.setHeaders(Map.of());
        env.setRetryCount(4);
        env.setMaxRetries(5);
        env.setOriginalMessageId("id2");

        assertEquals("t2", env.getTopic());
        assertEquals(2, env.getPartitionId());
        assertEquals("p2", env.getPayload());
        assertEquals("k2", env.getKey());
        assertEquals(Map.of(), env.getHeaders());
        assertEquals(4, env.getRetryCount());
        assertEquals(5, env.getMaxRetries());
        assertEquals("id2", env.getOriginalMessageId());
    }
}

