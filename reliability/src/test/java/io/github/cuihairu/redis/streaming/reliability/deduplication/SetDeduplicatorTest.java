package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.junit.jupiter.api.Test;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SetDeduplicatorTest {

    @Test
    void nullElementIsNeverDuplicateAndDoesNotMutate() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RSet<String> set = mock(RSet.class);
        when(redisson.<String>getSet("s")).thenReturn(set);

        SetDeduplicator<String> dedup = new SetDeduplicator<>(redisson, "s", (String v) -> v);

        assertFalse(dedup.isDuplicate(null));
        assertFalse(dedup.checkAndMark(null));
        dedup.markAsSeen(null);

        verifyNoInteractions(set);
    }

    @Test
    void checkAndMarkReturnsTrueOnlyForExistingKey() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RSet<String> set = mock(RSet.class);
        when(redisson.<String>getSet("s")).thenReturn(set);

        when(set.add("k1")).thenReturn(true);
        when(set.add("k2")).thenReturn(false);

        SetDeduplicator<String> dedup = new SetDeduplicator<>(redisson, "s", (String v) -> v);

        assertFalse(dedup.checkAndMark("k1"));
        assertTrue(dedup.checkAndMark("k2"));
    }

    @Test
    void containsKeyAndRemoveDelegateToSet() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RSet<String> set = mock(RSet.class);
        when(redisson.<String>getSet("s")).thenReturn(set);

        when(set.contains("k")).thenReturn(true);
        when(set.remove("k")).thenReturn(true);

        SetDeduplicator<String> dedup = new SetDeduplicator<>(redisson, "s", (String v) -> v);

        assertTrue(dedup.containsKey("k"));
        assertTrue(dedup.remove("k"));
    }
}
