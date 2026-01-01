package io.github.cuihairu.redis.streaming.aggregation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AggregationFunction interface
 */
class AggregationFunctionTest {

    @Test
    void testInterfaceIsFunctionalInterface() {
        // Given & When & Then
        assertTrue(AggregationFunction.class.isAnnotationPresent(FunctionalInterface.class));
    }

    @Test
    void testInterfaceHasApplyMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(AggregationFunction.class.getMethod("apply", Collection.class));
    }

    @Test
    void testInterfaceHasGetNameMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(AggregationFunction.class.getMethod("getName"));
    }

    @Test
    void testSimpleSumAggregation() {
        // Given
        AggregationFunction<Integer, Integer> sumAggregation = values -> {
            int sum = 0;
            for (Integer value : values) {
                sum += value;
            }
            return sum;
        };

        List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5);

        // When
        Integer result = sumAggregation.apply(numbers);

        // Then
        assertEquals(15, result);
    }

    @Test
    void testAggregationWithEmptyCollection() {
        // Given
        AggregationFunction<Integer, Integer> sumAggregation = values -> {
            int sum = 0;
            for (Integer value : values) {
                sum += value;
            }
            return sum;
        };

        // When
        Integer result = sumAggregation.apply(Collections.emptyList());

        // Then
        assertEquals(0, result);
    }

    @Test
    void testAggregationWithNullElements() {
        // Given
        AggregationFunction<String, Integer> lengthAggregation = values -> {
            int sum = 0;
            for (String value : values) {
                sum += value == null ? 0 : value.length();
            }
            return sum;
        };

        Collection<String> strings = Arrays.asList("hello", null, "world");

        // When
        Integer result = lengthAggregation.apply(strings);

        // Then
        assertEquals(10, result); // "hello" (5) + null (0) + "world" (5)
    }

    @Test
    void testAverageAggregation() {
        // Given
        AggregationFunction<Double, Double> avgAggregation = values -> {
            if (values.isEmpty()) {
                return 0.0;
            }
            double sum = 0.0;
            for (Double value : values) {
                sum += value;
            }
            return sum / values.size();
        };

        Collection<Double> numbers = Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0);

        // When
        Double result = avgAggregation.apply(numbers);

        // Then
        assertEquals(3.0, result, 0.001);
    }

    @Test
    void testMaxAggregation() {
        // Given
        AggregationFunction<Integer, Integer> maxAggregation = values -> {
            int max = Integer.MIN_VALUE;
            for (Integer value : values) {
                if (value > max) {
                    max = value;
                }
            }
            return max;
        };

        Collection<Integer> numbers = Arrays.asList(5, 2, 9, 1, 7);

        // When
        Integer result = maxAggregation.apply(numbers);

        // Then
        assertEquals(9, result);
    }

    @Test
    void testMinAggregation() {
        // Given
        AggregationFunction<Integer, Integer> minAggregation = values -> {
            int min = Integer.MAX_VALUE;
            for (Integer value : values) {
                if (value < min) {
                    min = value;
                }
            }
            return min;
        };

        Collection<Integer> numbers = Arrays.asList(5, 2, 9, 1, 7);

        // When
        Integer result = minAggregation.apply(numbers);

        // Then
        assertEquals(1, result);
    }

    @Test
    void testCountAggregation() {
        // Given
        AggregationFunction<String, Integer> countAggregation = Collection::size;

        Collection<String> strings = Arrays.asList("a", "b", "c", "d", "e");

        // When
        Integer result = countAggregation.apply(strings);

        // Then
        assertEquals(5, result);
    }

    @Test
    void testStringConcatenationAggregation() {
        // Given
        AggregationFunction<String, String> concatAggregation = values -> {
            StringBuilder sb = new StringBuilder();
            for (String value : values) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(value);
            }
            return sb.toString();
        };

        Collection<String> strings = Arrays.asList("apple", "banana", "cherry");

        // When
        String result = concatAggregation.apply(strings);

        // Then
        assertEquals("apple, banana, cherry", result);
    }

    @Test
    void testDefaultGetName() {
        // Given
        AggregationFunction<Integer, Integer> anonymousAgg = values -> 0;

        // When
        String name = anonymousAgg.getName();

        // Then - default implementation returns class simple name
        // For anonymous class, it will be something like "AggregationFunctionTest$$Lambda"
        assertNotNull(name);
    }

    @Test
    void testCustomGetName() {
        // Given
        AggregationFunction<Integer, Integer> customAgg = new AggregationFunction<Integer, Integer>() {
            @Override
            public Integer apply(Collection<Integer> values) {
                return values.stream().mapToInt(Integer::intValue).sum();
            }

            @Override
            public String getName() {
                return "CustomSum";
            }
        };

        // When
        String name = customAgg.getName();

        // Then
        assertEquals("CustomSum", name);
    }

    @Test
    void testAggregationWithSingleElement() {
        // Given
        AggregationFunction<Integer, Integer> sumAggregation = values -> {
            int sum = 0;
            for (Integer value : values) {
                sum += value;
            }
            return sum;
        };

        Collection<Integer> single = Collections.singletonList(42);

        // When
        Integer result = sumAggregation.apply(single);

        // Then
        assertEquals(42, result);
    }

    @Test
    void testAggregationWithLargeCollection() {
        // Given
        AggregationFunction<Integer, Integer> sumAggregation = values -> {
            int sum = 0;
            for (Integer value : values) {
                sum += value;
            }
            return sum;
        };

        List<Integer> largeList = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            largeList.add(i);
        }

        // When
        Integer result = sumAggregation.apply(largeList);

        // Then - sum of 1 to 1000 = 1000 * 1001 / 2 = 500500
        assertEquals(500500, result);
    }

    @Test
    void testAggregationWithNegativeNumbers() {
        // Given
        AggregationFunction<Integer, Integer> sumAggregation = values -> {
            int sum = 0;
            for (Integer value : values) {
                sum += value;
            }
            return sum;
        };

        Collection<Integer> numbers = Arrays.asList(-5, 10, -3, 7, -2);

        // When
        Integer result = sumAggregation.apply(numbers);

        // Then
        assertEquals(7, result); // -5 + 10 - 3 + 7 - 2 = 7
    }

    @Test
    void testAggregationWithMixedTypes() {
        // Given - aggregation that converts integers to strings
        AggregationFunction<Integer, String> joinAggregation = values -> {
            StringBuilder sb = new StringBuilder();
            for (Integer value : values) {
                if (sb.length() > 0) {
                    sb.append("-");
                }
                sb.append(value);
            }
            return sb.toString();
        };

        Collection<Integer> numbers = Arrays.asList(1, 2, 3);

        // When
        String result = joinAggregation.apply(numbers);

        // Then
        assertEquals("1-2-3", result);
    }

    @Test
    void testAggregationWithDuplicateValues() {
        // Given
        AggregationFunction<Integer, Integer> sumAggregation = values -> {
            int sum = 0;
            for (Integer value : values) {
                sum += value;
            }
            return sum;
        };

        Collection<Integer> numbers = Arrays.asList(5, 5, 5, 5);

        // When
        Integer result = sumAggregation.apply(numbers);

        // Then
        assertEquals(20, result);
    }

    @Test
    void testAggregationReturningCollection() {
        // Given - aggregation that filters and returns new collection
        AggregationFunction<Integer, Collection<Integer>> filterAggregation = values -> {
            List<Integer> result = new ArrayList<>();
            for (Integer value : values) {
                if (value > 5) {
                    result.add(value);
                }
            }
            return result;
        };

        Collection<Integer> numbers = Arrays.asList(1, 10, 3, 8, 2, 7);

        // When
        Collection<Integer> result = filterAggregation.apply(numbers);

        // Then
        assertEquals(3, result.size());
        assertTrue(result.contains(10));
        assertTrue(result.contains(8));
        assertTrue(result.contains(7));
    }

    @Test
    void testLambdaImplementation() {
        // Given
        AggregationFunction<Integer, Integer> productAggregation = values -> {
            int product = 1;
            for (Integer value : values) {
                product *= value;
            }
            return product;
        };

        Collection<Integer> numbers = Arrays.asList(2, 3, 4);

        // When
        Integer result = productAggregation.apply(numbers);

        // Then
        assertEquals(24, result); // 2 * 3 * 4 = 24
    }

    @Test
    void testMethodReferenceImplementation() {
        // Given - using method reference to size
        AggregationFunction<Integer, Integer> sizeAggregation = values -> values.size();

        List<Integer> list = Arrays.asList(1, 2, 3, 4, 5);

        // When
        Integer result = sizeAggregation.apply(list);

        // Then
        assertEquals(5, result);
    }

    @Test
    void testAggregationWithZeroValue() {
        // Given
        AggregationFunction<Integer, Integer> sumAggregation = values -> {
            int sum = 0;
            for (Integer value : values) {
                sum += value;
            }
            return sum;
        };

        Collection<Integer> numbers = Arrays.asList(0, 0, 0);

        // When
        Integer result = sumAggregation.apply(numbers);

        // Then
        assertEquals(0, result);
    }

    @Test
    void testAggregationThrowsException() {
        // Given - aggregation that throws on empty collection
        AggregationFunction<Integer, Integer> throwingAggregation = values -> {
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Collection is empty");
            }
            return values.iterator().next();
        };

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            throwingAggregation.apply(Collections.emptyList());
        });
    }

    @Test
    void testAggregationWithNullCollection() {
        // Given
        AggregationFunction<Integer, Integer> nullHandlingAggregation = values -> {
            if (values == null) {
                return 0;
            }
            return values.size();
        };

        // When
        Integer result = nullHandlingAggregation.apply(null);

        // Then
        assertEquals(0, result);
    }

    @Test
    void testComplexAggregationLogic() {
        // Given - aggregation that computes weighted average
        AggregationFunction<String, Double> weightedAvgAggregation = values -> {
            double sum = 0.0;
            double weightSum = 0.0;
            for (String value : values) {
                // Format: "item:weight,value"
                String[] parts = value.split(",");
                double weight = Double.parseDouble(parts[0].split(":")[1]);
                double val = Double.parseDouble(parts[1]);
                sum += weight * val;
                weightSum += weight;
            }
            return weightSum > 0 ? sum / weightSum : 0.0;
        };

        Collection<String> data = Arrays.asList(
            "item:1.0,10.0",
            "item:2.0,20.0",
            "item:3.0,30.0"
        );

        // When
        Double result = weightedAvgAggregation.apply(data);

        // Then - (1*10 + 2*20 + 3*30) / (1+2+3) = 140/6 = 23.33
        assertEquals(23.33, result, 0.01);
    }
}
