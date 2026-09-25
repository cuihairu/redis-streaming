package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the {@code TopKAnalyzer#removeItem} outcome branch for both an
 * existing and a missing item.
 */
class TopKAnalyzerRemoveItemCoverageTest {

    @SuppressWarnings("unchecked")
    @Test
    void removeItemReportsWhetherAnEntryWasRemoved() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);
        when(redisson.<String>getScoredSortedSet(anyString())).thenReturn(sortedSet);
        when(sortedSet.remove("known")).thenReturn(true);
        when(sortedSet.remove("ghost")).thenReturn(false);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 5, Duration.ofMinutes(10));

        assertTrue(analyzer.removeItem("products", "known"), "an existing item must report removal");
        assertFalse(analyzer.removeItem("products", "ghost"), "a missing item must report no removal");
    }
}
