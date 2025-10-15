package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.partition.HashPartitioner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HashPartitionerTest {
    @Test
    void testDeterministic() {
        HashPartitioner p = new HashPartitioner();
        int pc = 7;
        assertEquals(p.partition("a", pc), p.partition("a", pc));
        assertNotEquals(p.partition("a", pc), p.partition("b", pc));
        int idx = p.partition(null, pc);
        assertTrue(idx >= 0 && idx < pc);
    }
}

