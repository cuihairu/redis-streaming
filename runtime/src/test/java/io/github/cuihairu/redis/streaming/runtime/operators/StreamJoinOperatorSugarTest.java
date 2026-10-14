package io.github.cuihairu.redis.streaming.runtime.operators;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.join.JoinConfig;
import io.github.cuihairu.redis.streaming.join.JoinFunction;
import io.github.cuihairu.redis.streaming.join.JoinType;
import io.github.cuihairu.redis.streaming.join.JoinWindow;
import io.github.cuihairu.redis.streaming.join.operator.Envelope;
import io.github.cuihairu.redis.streaming.join.operator.StreamJoinOperator;
import io.github.cuihairu.redis.streaming.runtime.internal.InMemoryDataStream;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@link StreamJoinOperator#join} sugar over two mergeable {@code DataStream}s on the
 * in-memory engine: envelope tagging from the config's own selectors, union merge, keyBy on
 * the envelope's join key, and the operator — compared differentially against the manual
 * envelope path it replaces.
 */
class StreamJoinOperatorSugarTest {

    private static JoinConfig<String, String, String> joinConfig(JoinType type) {
        return JoinConfig.<String, String, String>builder()
                .joinType(type)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(30)))
                .leftKeySelector((String l) -> l.split(":")[1])
                .rightKeySelector((String r) -> r.split(":")[1])
                .leftTimestampExtractor(l -> 1000L)
                .rightTimestampExtractor(r -> 1000L)
                .build();
    }

    private static InMemoryDataStream<String> leftStream() {
        return new InMemoryDataStream<>(() -> List.of("o1:alice", "o2:bob").iterator());
    }

    private static InMemoryDataStream<String> rightStream() {
        return new InMemoryDataStream<>(() -> List.of("u1:alice", "u2:bob").iterator());
    }

    private static Function<String, Envelope<String, String, String>> tagLeft(JoinConfig<String, String, String> config) {
        return l -> Envelope.forLeft(
                config.getLeftKeySelector().apply(l),
                config.getLeftTimestampExtractor().apply(l),
                l);
    }

    private static Function<String, Envelope<String, String, String>> tagRight(JoinConfig<String, String, String> config) {
        return r -> Envelope.forRight(
                config.getRightKeySelector().apply(r),
                config.getRightTimestampExtractor().apply(r),
                r);
    }

    @Test
    void sugarJoinsTwoMemoryStreamsEndToEnd() {
        List<String> out = new ArrayList<>();
        StreamJoinOperator.join(leftStream(), rightStream(), joinConfig(JoinType.INNER), (l, r) -> l + "+" + r)
                .addSink(out::add);

        assertEquals(List.of("o1:alice+u1:alice", "o2:bob+u2:bob"), out);
    }

    @Test
    void sugarMatchesManualEnvelopePathOutput() {
        JoinConfig<String, String, String> config = joinConfig(JoinType.INNER);

        List<String> viaSugar = new ArrayList<>();
        StreamJoinOperator.join(leftStream(), rightStream(), config, (l, r) -> l + "+" + r)
                .addSink(viaSugar::add);

        // manual path: map both sides to envelopes, union, keyBy the envelope's join key
        List<String> viaManual = new ArrayList<>();
        leftStream().map(tagLeft(config))
                .union(rightStream().map(tagRight(config)))
                .keyBy(Envelope::getJoinKey)
                .process(StreamJoinOperator.asKeyedProcessFunction(config, (l, r) -> l + "+" + r))
                .addSink(viaManual::add);

        assertEquals(viaManual, viaSugar);
        assertEquals(List.of("o1:alice+u1:alice", "o2:bob+u2:bob"), viaSugar);
    }

    @Test
    void sugarUsesConfiguredTimestampsForWindowPredicate() {
        // same key, but 99+ seconds apart inside a 30s window: no pair
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(30)))
                .leftKeySelector((String l) -> l.split(":")[1])
                .rightKeySelector((String r) -> r.split(":")[1])
                .leftTimestampExtractor(l -> 1000L)
                .rightTimestampExtractor(r -> 600_000L)
                .build();

        List<String> out = new ArrayList<>();
        StreamJoinOperator.join(leftStream(), rightStream(), config, (l, r) -> l + "+" + r)
                .addSink(out::add);

        assertTrue(out.isEmpty(), "elements outside the join window must not pair, got " + out);
    }

    @Test
    void sugarWithoutTimestampExtractorsFallsBackToCurrentTime() {
        // mirrors StreamJoiner: no extractor -> system time at tagging; both sides tagged
        // within the same run pair inside the window
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(30)))
                .leftKeySelector((String l) -> l.split(":")[1])
                .rightKeySelector((String r) -> r.split(":")[1])
                .build();

        List<String> out = new ArrayList<>();
        StreamJoinOperator.join(leftStream(), rightStream(), config, (l, r) -> l + "+" + r)
                .addSink(out::add);

        assertEquals(List.of("o1:alice+u1:alice", "o2:bob+u2:bob"), out);
    }

    @Test
    void sugarKeepsLeftOuterJoinSemantics() {
        InMemoryDataStream<String> right = new InMemoryDataStream<>(() -> List.<String>of().iterator());

        List<String> out = new ArrayList<>();
        StreamJoinOperator.join(leftStream(), right, joinConfig(JoinType.LEFT), (l, r) -> l + "+" + r)
                .addSink(out::add);

        assertEquals(List.of("o1:alice+null", "o2:bob+null"), out);
    }

    @Test
    void sugarRejectsNullArguments() {
        JoinConfig<String, String, String> config = joinConfig(JoinType.INNER);
        JoinFunction<String, String, String> fn = (l, r) -> l;

        assertThrows(NullPointerException.class, () -> StreamJoinOperator.join(null, rightStream(), config, fn));
        assertThrows(NullPointerException.class, () -> StreamJoinOperator.join(leftStream(), null, config, fn));
        assertThrows(NullPointerException.class, () -> StreamJoinOperator.join(leftStream(), rightStream(), null, fn));
        assertThrows(NullPointerException.class, () -> StreamJoinOperator.join(leftStream(), rightStream(), config, null));
    }

    @Test
    void sugarRejectsInvalidConfig() {
        // retention shorter than the window: valid selectors, invalid per validate()
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(30)))
                .leftKeySelector((String l) -> l.split(":")[1])
                .rightKeySelector((String r) -> r.split(":")[1])
                .stateRetentionTime(1000L)
                .build();

        List<String> out = new ArrayList<>();
        assertThrows(IllegalArgumentException.class,
                () -> StreamJoinOperator.join(leftStream(), rightStream(), config, (l, r) -> l).addSink(out::add));
    }
}
