package io.github.cuihairu.redis.streaming.join;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StreamJoinerTest {

    @Data
    @AllArgsConstructor
    static class Order {
        String orderId;
        String userId;
        double amount;
        long timestamp;
    }

    @Data
    @AllArgsConstructor
    static class User {
        String userId;
        String name;
        long timestamp;
    }

    @Data
    @AllArgsConstructor
    static class EnrichedOrder {
        String orderId;
        String userName;
        double amount;
    }

    private StreamJoiner<Order, User, String, EnrichedOrder> joiner;

    @BeforeEach
    void setUp() {
        JoinConfig<Order, User, String> config = JoinConfig.<Order, User, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Order::getUserId)
                .rightKeySelector(User::getUserId)
                .leftTimestampExtractor(Order::getTimestamp)
                .rightTimestampExtractor(User::getTimestamp)
                .build();

        JoinFunction<Order, User, EnrichedOrder> joinFunc = (order, user) ->
                new EnrichedOrder(order.getOrderId(), user.getName(), order.getAmount());

        joiner = new StreamJoiner<>(config, joinFunc);
    }

    @Test
    void testInnerJoinWithMatch() throws Exception {
        User user = new User("user1", "Alice", 1000);
        Order order = new Order("order1", "user1", 99.99, 1000);

        // Process user first
        List<EnrichedOrder> results1 = joiner.processRight(user);
        assertEquals(0, results1.size()); // No order yet

        // Process order
        List<EnrichedOrder> results2 = joiner.processLeft(order);
        assertEquals(1, results2.size());
        assertEquals("order1", results2.get(0).getOrderId());
        assertEquals("Alice", results2.get(0).getUserName());
        assertEquals(99.99, results2.get(0).getAmount());
    }

    @Test
    void testInnerJoinWithMatchWhenLeftArrivesFirst() throws Exception {
        User user = new User("user1", "Alice", 1000);
        Order order = new Order("order1", "user1", 99.99, 1000);

        // Process order first
        List<EnrichedOrder> results1 = joiner.processLeft(order);
        assertEquals(0, results1.size()); // No user yet

        // Process user later should still produce the match
        List<EnrichedOrder> results2 = joiner.processRight(user);
        assertEquals(1, results2.size());
        assertEquals("order1", results2.get(0).getOrderId());
        assertEquals("Alice", results2.get(0).getUserName());
        assertEquals(99.99, results2.get(0).getAmount());
    }

    @Test
    void testInnerJoinWithoutMatch() throws Exception {
        Order order = new Order("order1", "user1", 99.99, 1000);

        // Process order without matching user
        List<EnrichedOrder> results = joiner.processLeft(order);
        assertEquals(0, results.size()); // No match, no output for INNER join
    }

    @Test
    void testJoinWithinWindow() throws Exception {
        User user = new User("user1", "Alice", 1000);
        Order order1 = new Order("order1", "user1", 99.99, 1000);
        Order order2 = new Order("order2", "user1", 49.99, 10999); // Within 10s window

        joiner.processRight(user);
        List<EnrichedOrder> results1 = joiner.processLeft(order1);
        List<EnrichedOrder> results2 = joiner.processLeft(order2);

        assertEquals(1, results1.size());
        assertEquals(1, results2.size());
    }

    @Test
    void testJoinOutsideWindow() throws Exception {
        User user = new User("user1", "Alice", 1000);
        Order order = new Order("order1", "user1", 99.99, 12000); // 11 seconds later

        joiner.processRight(user);
        List<EnrichedOrder> results = joiner.processLeft(order);

        assertEquals(0, results.size()); // Outside 10s window
    }

    @Test
    void testLeftJoin() throws Exception {
        JoinConfig<Order, User, String> leftJoinConfig = JoinConfig.<Order, User, String>builder()
                .joinType(JoinType.LEFT)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Order::getUserId)
                .rightKeySelector(User::getUserId)
                .leftTimestampExtractor(Order::getTimestamp)
                .rightTimestampExtractor(User::getTimestamp)
                .build();

        JoinFunction<Order, User, EnrichedOrder> leftJoinFunc = (order, user) ->
                new EnrichedOrder(
                        order.getOrderId(),
                        user != null ? user.getName() : "Unknown",
                        order.getAmount()
                );

        StreamJoiner<Order, User, String, EnrichedOrder> leftJoiner =
                new StreamJoiner<>(leftJoinConfig, leftJoinFunc);

        Order order = new Order("order1", "user1", 99.99, 1000);

        // Process order without matching user
        List<EnrichedOrder> results = leftJoiner.processLeft(order);
        assertEquals(1, results.size()); // LEFT join emits even without match
        assertEquals("Unknown", results.get(0).getUserName());
    }

    @Test
    void testRightJoin() throws Exception {
        JoinConfig<Order, User, String> rightJoinConfig = JoinConfig.<Order, User, String>builder()
                .joinType(JoinType.RIGHT)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Order::getUserId)
                .rightKeySelector(User::getUserId)
                .leftTimestampExtractor(Order::getTimestamp)
                .rightTimestampExtractor(User::getTimestamp)
                .build();

        JoinFunction<Order, User, EnrichedOrder> rightJoinFunc = (order, user) ->
                new EnrichedOrder(
                        order != null ? order.getOrderId() : "no-order",
                        user.getName(),
                        order != null ? order.getAmount() : 0.0
                );

        StreamJoiner<Order, User, String, EnrichedOrder> rightJoiner =
                new StreamJoiner<>(rightJoinConfig, rightJoinFunc);

        User user = new User("user1", "Alice", 1000);

        // Process user without matching order
        List<EnrichedOrder> results = rightJoiner.processRight(user);
        assertEquals(1, results.size()); // RIGHT join emits even without match
        assertEquals("Alice", results.get(0).getUserName());
        assertEquals("no-order", results.get(0).getOrderId());
    }

    @Test
    void testFullOuterJoinEmitsUnmatchedBothSides() throws Exception {
        JoinConfig<Order, User, String> fullOuterConfig = JoinConfig.<Order, User, String>builder()
                .joinType(JoinType.FULL_OUTER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Order::getUserId)
                .rightKeySelector(User::getUserId)
                .leftTimestampExtractor(Order::getTimestamp)
                .rightTimestampExtractor(User::getTimestamp)
                .build();

        JoinFunction<Order, User, String> func = (order, user) -> {
            String orderId = order == null ? "null" : order.getOrderId();
            String userId = user == null ? "null" : user.getUserId();
            return orderId + ":" + userId;
        };

        StreamJoiner<Order, User, String, String> fullOuter = new StreamJoiner<>(fullOuterConfig, func);

        Order order = new Order("order1", "order-only", 1.0, 1000);
        User user = new User("user-only", "Alice", 1000);

        assertEquals(List.of("order1:null"), fullOuter.processLeft(order));
        assertEquals(List.of("null:user-only"), fullOuter.processRight(user));
    }

    @Test
    void testMultipleMatches() throws Exception {
        User user = new User("user1", "Alice", 1000);
        Order order1 = new Order("order1", "user1", 99.99, 1000);
        Order order2 = new Order("order2", "user1", 49.99, 1500);

        joiner.processRight(user);
        List<EnrichedOrder> results1 = joiner.processLeft(order1);
        List<EnrichedOrder> results2 = joiner.processLeft(order2);

        assertEquals(1, results1.size());
        assertEquals(1, results2.size());
        assertEquals("order1", results1.get(0).getOrderId());
        assertEquals("order2", results2.get(0).getOrderId());
    }

    @Test
    void testBufferSize() throws Exception {
        User user1 = new User("user1", "Alice", 1000);
        User user2 = new User("user2", "Bob", 1000);
        Order order1 = new Order("order1", "user1", 99.99, 1000);

        joiner.processRight(user1);
        joiner.processRight(user2);
        joiner.processLeft(order1);

        assertTrue(joiner.getRightBufferSize() > 0);
        assertTrue(joiner.getLeftBufferSize() > 0);
    }

    @Test
    void testClear() throws Exception {
        User user = new User("user1", "Alice", 1000);
        joiner.processRight(user);

        assertTrue(joiner.getRightBufferSize() > 0);

        joiner.clear();

        assertEquals(0, joiner.getLeftBufferSize());
        assertEquals(0, joiner.getRightBufferSize());
    }

    @Test
    void testConfigValidation() {
        assertThrows(IllegalArgumentException.class, () -> {
            JoinConfig<Order, User, String> invalidConfig = JoinConfig.<Order, User, String>builder()
                    .joinType(null) // Invalid: null join type
                    .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                    .leftKeySelector(Order::getUserId)
                    .rightKeySelector(User::getUserId)
                    .build();
            invalidConfig.validate();
        });
    }
}
