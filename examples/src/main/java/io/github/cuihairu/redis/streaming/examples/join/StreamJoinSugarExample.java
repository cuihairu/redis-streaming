package io.github.cuihairu.redis.streaming.examples.join;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.join.JoinConfig;
import io.github.cuihairu.redis.streaming.join.JoinType;
import io.github.cuihairu.redis.streaming.join.JoinWindow;
import io.github.cuihairu.redis.streaming.join.operator.StreamJoinOperator;
import io.github.cuihairu.redis.streaming.runtime.internal.InMemoryDataStream;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal runnable example of the join sugar: {@link StreamJoinOperator#join} turns two plain
 * streams into a joined stream in one call — envelope tagging, merge, keyed routing and the
 * join operator are wired for you, using the {@link JoinConfig}'s own key selectors and
 * timestamp extractors.
 *
 * <p>Runs on the in-memory engine (no Redis needed):</p>
 * <pre>{@code
 * ./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.join.StreamJoinSugarExample
 * }</pre>
 *
 * <p>Two variants are shown: an INNER join (only matched pairs) and a LEFT OUTER join where each
 * left element is emitted with a {@code null} right half as soon as it is seen and re-emitted
 * with its peer when the right side arrives within the window — the reconciliation-alert shape
 * used by {@code examples/.../itemaudit/}.</p>
 */
@Slf4j
public class StreamJoinSugarExample {

    /** A business event on the left stream. */
    public record Order(String orderId, String userId, long amount, long eventTime) {
    }

    /** A reference record on the right stream. */
    public record User(String userId, String name, long eventTime) {
    }

    /** Observable outcome of one demo run. */
    record Snapshot(List<String> innerJoined, List<String> leftOuterJoined) {
    }

    public static void main(String[] args) throws Exception {
        new StreamJoinSugarExample().run();
    }

    public Snapshot run() {
        List<Order> orders = List.of(
                new Order("o1", "alice", 100, 1_000),
                new Order("o2", "bob", 50, 1_000),
                new Order("o3", "carol", 75, 1_000));   // no matching user
        List<User> users = List.of(
                new User("alice", "Alice", 1_000),
                new User("bob", "Bob", 1_000));

        List<String> inner = new ArrayList<>();
        joinOrdersToUsers(JoinType.INNER, orders, users).addSink(inner::add);

        List<String> leftOuter = new ArrayList<>();
        joinOrdersToUsers(JoinType.LEFT, orders, users).addSink(leftOuter::add);

        log.info("INNER join: {}", inner);
        log.info("LEFT OUTER join: {}", leftOuter);
        return new Snapshot(inner, leftOuter);
    }

    /**
     * One call wires the whole join: the sugar tags both sides into envelopes with the config's
     * selectors, merges them, routes by the envelope's join key, and runs the operator.
     */
    private static DataStream<String> joinOrdersToUsers(JoinType type, List<Order> orders, List<User> users) {
        JoinConfig<Order, User, String> config = JoinConfig.<Order, User, String>builder()
                .joinType(type)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(30)))
                .leftKeySelector(Order::userId)
                .rightKeySelector(User::userId)
                .leftTimestampExtractor(Order::eventTime)
                .rightTimestampExtractor(User::eventTime)
                .build();

        DataStream<Order> left = new InMemoryDataStream<>(() -> orders.iterator());
        DataStream<User> right = new InMemoryDataStream<>(() -> users.iterator());

        return StreamJoinOperator.join(left, right, config,
                (order, user) -> user == null
                        ? order.orderId() + "|unmatched"                 // LEFT OUTER: no peer in window
                        : order.orderId() + "|" + user.name() + "|" + order.amount());
    }
}
