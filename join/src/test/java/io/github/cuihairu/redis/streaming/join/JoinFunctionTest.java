package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JoinFunction
 */
class JoinFunctionTest {

    @Test
    void testJoinFunctionWithLambda() throws Exception {
        JoinFunction<String, Integer, String> joinFunction = (left, right) -> left + ":" + right;

        String result = joinFunction.join("hello", 42);
        assertEquals("hello:42", result);
    }

    @Test
    void testJoinFunctionWithNullLeft() throws Exception {
        JoinFunction<String, Integer, String> joinFunction = (left, right) -> {
            if (left == null) return "null:" + right;
            return left + ":" + right;
        };

        String result = joinFunction.join(null, 42);
        assertEquals("null:42", result);
    }

    @Test
    void testJoinFunctionWithNullRight() throws Exception {
        JoinFunction<String, Integer, String> joinFunction = (left, right) -> {
            if (right == null) return left + ":null";
            return left + ":" + right;
        };

        String result = joinFunction.join("hello", null);
        assertEquals("hello:null", result);
    }

    @Test
    void testJoinFunctionWithBothNull() throws Exception {
        JoinFunction<String, String, String> joinFunction = (left, right) -> {
            if (left == null && right == null) return "both:null";
            return left + ":" + right;
        };

        String result = joinFunction.join(null, null);
        assertEquals("both:null", result);
    }

    @Test
    void testJoinFunctionOfBiFunction() throws Exception {
        JoinFunction<String, Integer, String> joinFunction = JoinFunction.of(
                (left, right) -> left + "-" + right
        );

        String result = joinFunction.join("test", 123);
        assertEquals("test-123", result);
    }

    @Test
    void testJoinFunctionWithComplexTypes() throws Exception {
        class Person {
            final String name;
            Person(String name) { this.name = name; }
        }

        class Address {
            final String city;
            Address(String city) { this.city = city; }
        }

        JoinFunction<Person, Address, String> joinFunction = (person, address) -> {
            return person.name + " lives in " + address.city;
        };

        Person person = new Person("Alice");
        Address address = new Address("Beijing");

        String result = joinFunction.join(person, address);
        assertEquals("Alice lives in Beijing", result);
    }

    @Test
    void testJoinFunctionThrowsException() {
        JoinFunction<String, String, String> joinFunction = (left, right) -> {
            throw new IllegalArgumentException("Invalid join");
        };

        assertThrows(IllegalArgumentException.class, () -> joinFunction.join("a", "b"));
    }

    @Test
    void testJoinFunctionImplementsSerializable() {
        JoinFunction<String, Integer, String> joinFunction = (left, right) -> left + ":" + right;

        // Verify that the join function implements Serializable through its interface
        assertInstanceOf(Serializable.class, joinFunction);
    }

    @Test
    void testJoinFunctionWithPrimitiveTypes() throws Exception {
        JoinFunction<Integer, Long, Double> joinFunction = (left, right) -> {
            return (double) left + right;
        };

        Double result = joinFunction.join(10, 20L);
        assertEquals(30.0, result, 0.001);
    }

    @Test
    void testJoinFunctionForInnerJoin() throws Exception {
        // Inner join: both left and right are non-null
        JoinFunction<String, String, String> innerJoin = (left, right) -> {
            if (left == null || right == null) {
                throw new IllegalArgumentException("Inner join requires both values");
            }
            return left + "-" + right;
        };

        assertDoesNotThrow(() -> innerJoin.join("left", "right"));
        assertThrows(IllegalArgumentException.class, () -> innerJoin.join("left", null));
        assertThrows(IllegalArgumentException.class, () -> innerJoin.join(null, "right"));
    }

    @Test
    void testJoinFunctionForLeftJoin() throws Exception {
        // Left join: left can be null, right must be non-null
        JoinFunction<String, String, String> leftJoin = (left, right) -> {
            if (right == null) {
                throw new IllegalArgumentException("Left join requires right value");
            }
            return left == null ? "null-" + right : left + "-" + right;
        };

        assertEquals("left-right", leftJoin.join("left", "right"));
        assertEquals("null-right", leftJoin.join(null, "right"));
        assertThrows(IllegalArgumentException.class, () -> leftJoin.join("left", null));
    }

    @Test
    void testJoinFunctionForRightJoin() throws Exception {
        // Right join: right can be null, left must be non-null
        JoinFunction<String, String, String> rightJoin = (left, right) -> {
            if (left == null) {
                throw new IllegalArgumentException("Right join requires left value");
            }
            return left + "-" + (right == null ? "null" : right);
        };

        assertEquals("left-right", rightJoin.join("left", "right"));
        assertEquals("left-null", rightJoin.join("left", null));
        assertThrows(IllegalArgumentException.class, () -> rightJoin.join(null, "right"));
    }

    @Test
    void testJoinFunctionForFullJoin() throws Exception {
        // Full join: both left and right can be null
        JoinFunction<String, String, String> fullJoin = (left, right) -> {
            String leftStr = left == null ? "null" : left;
            String rightStr = right == null ? "null" : right;
            return leftStr + "-" + rightStr;
        };

        assertEquals("left-right", fullJoin.join("left", "right"));
        assertEquals("null-right", fullJoin.join(null, "right"));
        assertEquals("left-null", fullJoin.join("left", null));
        assertEquals("null-null", fullJoin.join(null, null));
    }

    @Test
    void testJoinFunctionWithMethodReference() throws Exception {
        class Calculator {
            String add(String a, String b) {
                return a + "+" + b;
            }
        }

        Calculator calc = new Calculator();
        JoinFunction<String, String, String> joinFunction = calc::add;

        String result = joinFunction.join("a", "b");
        assertEquals("a+b", result);
    }
}
