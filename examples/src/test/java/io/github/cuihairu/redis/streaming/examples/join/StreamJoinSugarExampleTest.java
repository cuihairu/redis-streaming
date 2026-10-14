package io.github.cuihairu.redis.streaming.examples.join;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the observable outcome of the join-sugar example: the INNER variant keeps only matched
 * pairs, the LEFT OUTER variant emits each left element with a null right half as soon as it is
 * seen and re-emits the matched pair when the peer arrives within the window.
 */
class StreamJoinSugarExampleTest {

    @Test
    @DisplayName("run() joins orders to users with the expected INNER and LEFT OUTER output")
    void runProducesExpectedInnerAndLeftOuterOutput() {
        StreamJoinSugarExample.Snapshot snapshot = new StreamJoinSugarExample().run();

        assertEquals(
                java.util.List.of("o1|Alice|100", "o2|Bob|50"),
                snapshot.innerJoined(),
                "INNER join must keep only the two matched orders");
        assertEquals(
                java.util.List.of(
                        "o1|unmatched", "o1|Alice|100",
                        "o2|unmatched", "o2|Bob|50",
                        "o3|unmatched"),
                snapshot.leftOuterJoined(),
                "LEFT OUTER join must emit the null half up front and the matched pair when the peer arrives");
    }
}
