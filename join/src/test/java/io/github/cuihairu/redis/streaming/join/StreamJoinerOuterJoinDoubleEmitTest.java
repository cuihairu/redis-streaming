package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B-36: pins the documented outer-join semantics — an element with no match at
 * arrival time is emitted immediately as {@code join(elem, null)}, and when its
 * peer arrives later (still inside the window) the pair is emitted as a second
 * {@code join(L, R)} record. There is no retraction; downstreams must tolerate
 * the double delivery. This test guards the contract so it cannot change
 * silently.
 */
class StreamJoinerOuterJoinDoubleEmitTest {

    private StreamJoiner<String, String, String, String> leftJoiner() {
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.LEFT)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(l -> "shared")
                .rightKeySelector(r -> "shared")
                .leftTimestampExtractor(v -> 1_000L)
                .rightTimestampExtractor(v -> 1_000L)
                .build();
        return new StreamJoiner<>(config, (l, r) -> "(" + l + "," + r + ")");
    }

    @Test
    void leftJoinEmitsUnmatchedThenMatchWhenThePeerArrivesLater() throws Exception {
        StreamJoiner<String, String, String, String> joiner = leftJoiner();

        assertEquals(List.of("(L,null)"), joiner.processLeft("L"),
                "no peer yet: the left element is emitted unmatched immediately");

        assertEquals(List.of("(L,R)"), joiner.processRight("R"),
                "the late peer still joins the buffered left element inside the window");
    }
}
